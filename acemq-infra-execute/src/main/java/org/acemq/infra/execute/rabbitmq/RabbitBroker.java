/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.infra.execute.rabbitmq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.execute.Broker;
import org.acemq.infra.execute.Observation;
import org.acemq.infra.execute.Observation.Reading;
import org.acemq.infra.execute.Topology;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.rabbitmq.admin.AdminException;
import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.PolicyInfo;
import org.acemq.rabbitmq.admin.QueueInfo;

/**
 * The eight verbs, against a real RabbitMQ.
 *
 * <p>docs/broker-agnostic.md's argument is that the seam sits at the level of intent and the
 * provider is then free to be as RabbitMQ-specific as it likes underneath, because a shovel
 * <em>is</em> how you move messages between clusters on RabbitMQ and a cutover tool that refused to
 * use one because it is not portable is a cutover tool that copies messages by hand. This class is
 * the underneath: shovels, federation upstreams, a definitions import, and the management API's
 * connection close.
 *
 * <p>Two of them are worth reading before trusting.
 *
 * <p><strong>{@link #mirror(Mirroring)} writes its policy with {@code apply-to: exchanges}, by
 * hand.</strong> {@code RabbitAdmin.putPolicy} writes {@code all}, which is right for the general
 * case and would, here, federate the queues as well as the exchanges. A federated queue pulls from
 * its upstream only when the upstream has no local consumers, so the mirror would sit empty while
 * the source was healthy and start draining it the moment the source's consumers stopped — which is
 * precisely what a cutover does on purpose. That is the accidental drain docs/message-state.md is
 * about, and it is one JSON field.
 *
 * <p><strong>{@link #measure(List)} can answer "I cannot see".</strong> The depths and counts come
 * from the management statistics database and the publish rate comes from a {@code message_stats}
 * block that is simply absent on a broker with {@code rates_mode = none}. Absent is not nought, and
 * the reading says which.
 */
public final class RabbitBroker implements Broker {

    /** The protocol a shovel and a federation link speak. */
    private static final String AMQP091 = "amqp091";

    /** Turns a lowerCamelCase word into the kebab-case the management API expects. */
    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");

    private final String name;
    private final ChangingAdmin admin;

    private RabbitBroker(String name, ChangingAdmin admin) {
        this.name = name;
        this.admin = admin;
    }

    /**
     * Connects to a cluster with a client that can change it.
     *
     * @param access where the cluster is and who to be
     * @return the broker
     */
    public static RabbitBroker open(ClusterAccess access) {
        return new RabbitBroker(access.name(), ChangingAdmin.open(access));
    }

    @Override
    public String name() {
        return name;
    }

    // ---------------------------------------------------------------- topology

    @Override
    public Topology snapshotTopology(Scope scope) {
        List<PolicyInfo> operatorPolicies = scope.parts().contains(TopologyPart.OPERATOR_POLICIES)
                ? admin.operatorPolicies() : List.of();
        return RabbitTopology.of(name, admin.vhost(), admin.definitions().asMap(), operatorPolicies);
    }

    @Override
    public void applyTopology(Topology topology, Scope scope) {
        if (!(topology instanceof RabbitTopology snapshot)) {
            // The seam is provider-scoped on purpose, and this is where that shows. A topology read
            // from another kind of broker is not a thing this one can import, and pretending
            // otherwise would mean inventing a translation nobody has asked for.
            throw new AdminException("this topology was not read from a RabbitMQ cluster, so it"
                    + " cannot be written onto " + name + ".");
        }
        List<TopologyPart> document = scope.parts().stream()
                .filter(part -> part != TopologyPart.OPERATOR_POLICIES).toList();
        if (!document.isEmpty()) {
            admin.importDefinitions(snapshot.documentFor(document));
        }
        if (scope.parts().contains(TopologyPart.OPERATOR_POLICIES)) {
            // One at a time, because a definitions document does not carry them -- and with the
            // source's own apply-to kept, because an operator policy moved from exchanges to
            // queues is a different policy wearing the same name.
            for (PolicyInfo policy : snapshot.operatorPolicies()) {
                admin.putOperatorPolicy(policy.name(), policy.pattern(), policy.definition(),
                        policy.priority(), policy.applyTo());
            }
        }
    }

    // ---------------------------------------------------------------- attachments

    @Override
    public List<Attachment> listAttachments() {
        // The management API answers "which connection is this channel on" and "how many consumers
        // are on this channel", and never "which connections are consuming" -- which is what the
        // close step's `role: consumer` selector matches on. So the two are joined here, exactly as
        // the probe joins them, because a close that guessed would close a publisher.
        Map<String, Integer> consuming = new HashMap<>();
        for (ChannelInfo channel : admin.channels()) {
            consuming.merge(channel.connectionName(), channel.consumerCount(), Integer::sum);
        }
        List<Attachment> attachments = new ArrayList<>();
        for (ConnectionInfo connection : admin.connections()) {
            attachments.add(new Attachment(connection.name(), connection.user(),
                    consuming.getOrDefault(connection.name(), 0) > 0));
        }
        return attachments;
    }

    @Override
    public void detach(Attachment attachment, String reason) {
        admin.closeConnection(attachment.name(), reason);
    }

    // ---------------------------------------------------------------- movement

    @Override
    public Movement drain(Drainage drainage) {
        List<String> declared = new ArrayList<>();
        for (String queue : drainage.queues()) {
            String shovel = drainage.label() + "-" + queue;
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("src-protocol", AMQP091);
            definition.put("src-uri", drainage.sourceUri());
            definition.put("src-queue", queue);
            definition.put("dest-protocol", AMQP091);
            definition.put("dest-uri", drainage.destinationUri());
            definition.put("dest-queue", queue);
            definition.put("ack-mode", kebab(drainage.ackMode()));
            // The setting that makes a drain finish: the shovel removes itself once it has moved
            // the number of messages the queue held when it started. That is what lets a guard ask
            // "has the movement gone" rather than only "does the statistics database say nought".
            definition.put("src-delete-after", kebab(drainage.deleteAfter()));
            admin.declareShovel(shovel, definition);
            declared.add(shovel);
        }
        return new Movement(drainage.label(), name, declared);
    }

    @Override
    public Movement mirror(Mirroring mirroring) {
        Map<String, Object> settings = new LinkedHashMap<>();
        mirroring.prefetch().ifPresent(prefetch -> settings.put("prefetch-count", prefetch));
        mirroring.ackMode().ifPresent(mode -> settings.put("ack-mode", kebab(mode)));
        admin.putFederationUpstream(mirroring.label(), mirroring.sourceUri(), settings);

        // apply-to: exchanges, and nothing else will do. See the class comment.
        admin.putPolicy(mirroring.label(), anyOf(mirroring.exchanges()),
                Map.of("federation-upstream", mirroring.label()), 1, "exchanges");
        return new Movement(mirroring.label(), name,
                List.of("upstream " + mirroring.label(), "policy " + mirroring.label()));
    }

    @Override
    public boolean finished(Movement movement) {
        List<String> shovels = admin.parameterNames(ChangingAdmin.SHOVEL);
        List<String> upstreams = admin.parameterNames(ChangingAdmin.FEDERATION_UPSTREAM);
        List<String> policies = admin.policyNames();
        for (String part : movement.parts()) {
            boolean still = switch (kindOf(part)) {
                case UPSTREAM -> upstreams.contains(rest(part, UPSTREAM));
                case POLICY -> policies.contains(rest(part, POLICY));
                case SHOVEL -> shovels.contains(part);
            };
            if (still) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void cancel(Movement movement) {
        for (String part : movement.parts()) {
            switch (kindOf(part)) {
                case UPSTREAM -> admin.deleteFederationUpstream(rest(part, UPSTREAM));
                case POLICY -> admin.deletePolicy(rest(part, POLICY));
                case SHOVEL -> admin.deleteShovel(part);
            }
        }
    }

    /** What a movement is made of. A drain is shovels; a mirror is an upstream and a policy. */
    private enum Piece {
        SHOVEL, UPSTREAM, POLICY
    }

    private static final String UPSTREAM = "upstream ";
    private static final String POLICY = "policy ";

    private static Piece kindOf(String part) {
        if (part.startsWith(UPSTREAM)) {
            return Piece.UPSTREAM;
        }
        return part.startsWith(POLICY) ? Piece.POLICY : Piece.SHOVEL;
    }

    private static String rest(String part, String prefix) {
        return part.substring(prefix.length());
    }

    // ---------------------------------------------------------------- measure

    @Override
    public Observation measure(List<String> queues) {
        List<QueueInfo> matching = admin.queues().stream()
                .filter(queue -> queues.isEmpty() || queues.contains(queue.name()))
                .toList();

        if (!queues.isEmpty() && matching.size() < queues.size()) {
            // A queue a guard was told to watch and the broker does not have. Reporting nought for
            // it would say the drain had finished; the queue may have been deleted underneath the
            // cutover, or the vhost may be the wrong one, and neither is something to guess past.
            List<String> absent = new ArrayList<>(queues);
            absent.removeAll(matching.stream().map(QueueInfo::name).toList());
            Reading missing = Reading.unobservable(name + " has no queue named "
                    + String.join(", ", absent) + " in " + admin.vhost());
            return new Observation(missing, missing, missing, publishRate());
        }

        return new Observation(
                Reading.of(matching.stream().mapToLong(QueueInfo::messages).sum()),
                Reading.of(matching.stream().mapToLong(QueueInfo::messagesUnacknowledged).sum()),
                Reading.of(matching.stream().mapToLong(QueueInfo::consumers).sum()),
                publishRate());
    }

    /**
     * The publish rate, rounded up.
     *
     * <p>Up rather than to nearest, because the only target anybody writes is nought and the
     * question being asked is "has publishing stopped". A rate of 0.4 a second is four messages
     * every ten seconds arriving on a cluster somebody is about to empty, and rounding that to
     * nought would answer yes to a question whose answer is no.
     */
    private Reading publishRate() {
        Optional<Double> rate = admin.publishRate();
        return rate.map(value -> Reading.of((long) Math.ceil(value)))
                .orElseGet(() -> Reading.unobservable("the management API reported no message_stats"
                        + " for " + admin.vhost() + " on " + name + ", which is what a broker with"
                        + " rates_mode = none or its metrics collector disabled looks like. That is"
                        + " not the same as a publish rate of nought and this will not read it as"
                        + " one"));
    }

    // ---------------------------------------------------------------- announce

    @Override
    public boolean announce(Envelope envelope) {
        return admin.publish(envelope.exchange(), envelope.routingKey(), envelope.payload());
    }

    @Override
    public void close() {
        admin.close();
    }

    // ---------------------------------------------------------------- odds and ends

    /**
     * {@code onConfirm} as the file writes it, {@code on-confirm} as the broker wants it.
     *
     * <p>docs/library.md explains why the file keeps these as written: {@code ackMode} and
     * {@code deleteAfter} each appear exactly once in the documentation, as one example, with no
     * list of alternatives — so the parser does not close a vocabulary the format has not closed.
     * The translation therefore lands here, in the one place that knows what RabbitMQ calls them. A
     * value already written in kebab case passes through unchanged, which is what lets somebody
     * write {@code on-confirm} and have it work.
     */
    private static String kebab(String written) {
        return CAMEL.matcher(written).replaceAll("$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * {@code ^(orders\.new|orders\.audit)$} — the pattern a federation policy matches on.
     *
     * <p>Escaped a character at a time rather than with {@code Pattern.quote}, which wraps its
     * argument in {@code \Q...\E}. The broker compiles this with Erlang's {@code re} module and not
     * with Java's, so a construct Java understands is not by itself an argument that RabbitMQ will;
     * a backslash in front of every metacharacter is understood by both and by anybody reading the
     * policy in the management UI afterwards.
     */
    private static String anyOf(List<String> names) {
        return "^(" + String.join("|", names.stream().map(RabbitBroker::escape).toList()) + ")$";
    }

    private static String escape(String name) {
        StringBuilder escaped = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (!Character.isLetterOrDigit(character) && character != '_') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
