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
package org.acemq.infra.provider.rabbitmq;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.Prober;
import org.acemq.infra.provider.rabbitmq.ManagementEndpoint.Presence;
import org.acemq.rabbitmq.admin.AdminException;
import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.ConsumerInfo;
import org.acemq.rabbitmq.admin.CurrentUser;
import org.acemq.rabbitmq.admin.FeatureFlagInfo;
import org.acemq.rabbitmq.admin.QueueInfo;

/**
 * What can this cluster actually do? Asked of a real RabbitMQ.
 *
 * <p>A dozen GETs and one conclusion. The version and the user's tags come from the management
 * API; the plugins come from whether their endpoints answer at all, which is a distinction
 * {@link ManagementEndpoint} exists to recover; the counts come from the virtual host the file
 * named. {@link RabbitCapabilities} turns the lot into verdicts.
 *
 * <p><strong>It writes nothing.</strong> Not as a matter of care: the client it holds is a
 * {@link ReadOnlyAdmin}, which does not have a writing method on it to call. The one request that
 * does not go through that client is a GET whose body is discarded.
 *
 * <p>Failures are not swallowed. A probe that quietly returned a cluster with no capabilities when
 * the broker was unreachable would produce a plan that refuses every step for the wrong reason,
 * and the operator would go looking for a missing plugin on a cluster that is simply down.
 */
public final class RabbitProbe implements Prober {

    /** Where a shovel would be declared and therefore where the plugin shows itself. */
    private static final String SHOVELS = "/api/shovels";

    /**
     * Likewise for federation: the links endpoint, which needs both halves of the plugin.
     *
     * <p>Deliberately the unscoped path rather than {@code /api/federation-links/<vhost>}. A
     * broker without the plugin answers <strong>400</strong> to the scoped form — the route does
     * not exist, so the encoded vhost is read as something else entirely — and 400 is not a
     * sentence anything can conclude from. The unscoped path answers a clean 404, which is the
     * answer the question deserves.
     */
    private static final String FEDERATION_LINKS = "/api/federation-links";

    /** The feature flag a broker sets when it knows what a stream queue is. */
    private static final String STREAM_QUEUE = "stream_queue";

    /** The consumer argument that decides where a stream consumer resumes, and the whole problem. */
    private static final String STREAM_OFFSET = "x-stream-offset";

    @Override
    public ProbedCluster probe(ClusterAccess access) {
        try (ReadOnlyAdmin admin = ReadOnlyAdmin.open(access)) {
            return probe(access, admin);
        } catch (AdminException failure) {
            // The three ways this fails -- wrong password, wrong port, no management plugin --
            // all produce the same confusion later, so the name of the cluster the file gave is
            // put in front of whatever the client said about it.
            throw new AdminException("could not probe cluster '" + access.name() + "' at "
                    + access.redactedManagement() + ": " + failure.getMessage(), failure);
        }
    }

    private ProbedCluster probe(ClusterAccess access, ReadOnlyAdmin admin) {
        String version = admin.version();
        CurrentUser user = admin.whoami();
        // Read once and used twice: the capability set wants to know whether the listing could be
        // taken at all, and the inventory wants what was in it. Asking the broker the same
        // question twice would leave room for the two answers to differ, which for this one
        // listing is the difference between "the canary is safe" and "nobody can tell".
        Optional<List<ConsumerInfo>> attached = consumerListing(admin);
        BrokerFacts facts = new BrokerFacts(version, user.tags(),
                ManagementEndpoint.check(access, SHOVELS),
                ManagementEndpoint.check(access, FEDERATION_LINKS),
                streamQueues(admin), definitionsReadable(admin), attached.isPresent());

        ProbedCluster.Builder builder = ProbedCluster.named(access.name())
                .product("RabbitMQ")
                .version(version);
        builder.inventory(inventory(admin, builder, access.name(), attached));
        RabbitCapabilities.of(facts, builder);
        notes(access, facts, builder);
        return builder.build();
    }

    /**
     * What a plan should be told that the probe could not settle.
     *
     * <p>A cluster whose file asks for a TLS setting the client cannot apply is the case this
     * exists for. Saying nothing would mean an operator reads a plan, sees two clusters probed
     * successfully, and concludes their {@code caFile} was honoured.
     */
    private void notes(ClusterAccess access, BrokerFacts facts, ProbedCluster.Builder builder) {
        if (facts.shovel() == Presence.FORBIDDEN || facts.federation() == Presence.FORBIDDEN) {
            builder.note("these credentials cannot read the plugin endpoints, so the plugin"
                    + " facts above are what could be seen rather than what is true");
        }
        if (!access.management().startsWith("https")) {
            builder.note("the management API is reached over http, so these credentials cross the"
                    + " network in the clear");
        }
    }

    private Inventory inventory(ReadOnlyAdmin admin, ProbedCluster.Builder builder,
                                String cluster, Optional<List<ConsumerInfo>> attached) {
        Inventory.Builder inventory = Inventory.counting()
                .exchanges(counted(builder, "exchanges", admin::exchanges))
                .bindings(counted(builder, "bindings", admin::bindings))
                // Users and permissions are the two a monitoring-only account is refused, and a
                // probe that gave up there would be unable to report on the estate it is most
                // important to report on: one where the credentials cannot do what the plan will
                // ask. A count that could not be taken is nought and a note, not an exception.
                .users(counted(builder, "users", admin::users))
                .permissions(counted(builder, "permissions", () -> admin.permissions().size()))
                .policies(counted(builder, "policies", () -> admin.policies().size()))
                .operatorPolicies(counted(builder, "operator policies",
                        () -> admin.operatorPolicies().size()))
                // Federation upstreams and shovels are both runtime parameters, which is why a
                // topology copy that only looked at queues and exchanges would leave a cluster
                // looking complete and federating nothing.
                .parameters(counted(builder, "runtime parameters",
                        () -> admin.parameters("federation-upstream") + admin.parameters("shovel")));

        for (QueueInfo queue : listed(builder, "queues", admin::queues)) {
            inventory.queue(queue.name(), queue.type(), queue.messages(), queue.consumers());
        }

        // The management API answers "which connection is this channel on" and "how many consumers
        // are on this channel", and never "which connections are consuming" -- which is what the
        // close step's `role: consumer` selector has to match on. So the two are joined here.
        Map<String, Integer> consuming = new HashMap<>();
        Map<String, ChannelInfo> channels = new HashMap<>();
        for (ChannelInfo channel : listed(builder, "channels", admin::channels)) {
            consuming.merge(channel.connectionName(), channel.consumerCount(), Integer::sum);
            channels.put(channel.name(), channel);
        }
        for (ConnectionInfo connection : listed(builder, "connections", admin::connections)) {
            inventory.connection(connection.name(), connection.user(),
                    consuming.getOrDefault(connection.name(), 0));
        }

        consumers(inventory, cluster, attached, channels);
        return inventory.build();
    }

    /**
     * Who is consuming which queue, which is the one listing a canary cannot be safe without.
     *
     * <p>A third join, and the awkward one. {@code /api/consumers} names the queue and the channel
     * and does not name the user, so the user comes from the channel listing — and a consumer whose
     * channel is not in that listing is left with the channel's own name in the user's place rather
     * than dropped. A consumer missing from the check is a consumer the canary would move a queue
     * out from under, so the failure has to be visible rather than tidy: an unrecognised name is
     * not in anybody's {@code services:} list and the scope check refuses on it, which is the right
     * way for this to go wrong.
     */
    private void consumers(Inventory.Builder inventory, String cluster,
                           Optional<List<ConsumerInfo>> attached,
                           Map<String, ChannelInfo> channels) {
        if (attached.isEmpty()) {
            inventory.consumersUnreadable("these credentials cannot read /api/consumers on "
                    + cluster + ", which needs the monitoring or administrator tag");
            return;
        }
        for (ConsumerInfo consumer : attached.get()) {
            ChannelInfo channel = channels.get(consumer.channelName());
            String user = channel == null ? consumer.channelName() : channel.user();
            String connection = channel == null ? consumer.channelName() : channel.connectionName();
            Optional<String> offset = streamOffset(consumer);
            if (offset.isPresent()) {
                inventory.streamConsumer(consumer.queue(), connection, user, offset.get());
            } else {
                inventory.consumer(consumer.queue(), connection, user);
            }
        }
    }

    /**
     * The {@code x-stream-offset} a consumer asked for, as it asked for it.
     *
     * <p>As written, because the projection in the plan has to say what the client said. The value
     * is a word, a number or a timestamp depending on what the client wanted, so it is stringified
     * rather than parsed — a number turned into a word here would be a plan describing a setting
     * nobody chose.
     */
    private Optional<String> streamOffset(ConsumerInfo consumer) {
        Object offset = consumer.arguments() == null ? null
                : consumer.arguments().get(STREAM_OFFSET);
        return Optional.ofNullable(offset).map(String::valueOf);
    }

    /**
     * Every consumer, or nothing at all when the credentials are refused.
     *
     * <p>Empty means "could not be read" and never "there are none". That distinction is the whole
     * reason this returns an {@link Optional}: the two produce identical lists and mean opposite
     * things about whether a canary may run.
     */
    private Optional<List<ConsumerInfo>> consumerListing(ReadOnlyAdmin admin) {
        try {
            return Optional.ofNullable(admin.consumers());
        } catch (AdminException refused) {
            return Optional.empty();
        }
    }

    /**
     * A count, or nought and a note saying the credentials could not take it.
     *
     * <p>Silence would be worse than either. A plan that says "0 users" because the management
     * account may not read {@code /api/users} looks exactly like a plan for a cluster with no
     * users, and the second one is a cluster somebody would notice.
     */
    private int counted(ProbedCluster.Builder builder, String what, IntSupplier count) {
        try {
            return count.getAsInt();
        } catch (AdminException refused) {
            builder.note(what + " could not be read with these credentials, so the plan counts"
                    + " none");
            return 0;
        }
    }

    private <T> List<T> listed(ProbedCluster.Builder builder, String what, Supplier<List<T>> list) {
        try {
            return list.get();
        } catch (AdminException refused) {
            builder.note(what + " could not be read with these credentials, so the plan counts"
                    + " none");
            return List.of();
        }
    }

    private boolean streamQueues(ReadOnlyAdmin admin) {
        try {
            return admin.featureFlags().stream()
                    .filter(flag -> STREAM_QUEUE.equals(flag.name()))
                    .anyMatch(FeatureFlagInfo::isEnabled);
        } catch (AdminException unreadable) {
            // Feature flags arrived in 3.8 and the endpoint needs the administrator tag on some
            // versions. Not being able to read them is an answer about this probe rather than
            // about the broker, and the capability it feeds says as much.
            return false;
        }
    }

    private boolean definitionsReadable(ReadOnlyAdmin admin) {
        try {
            return admin.definitionsSize() > 0;
        } catch (AdminException refused) {
            return false;
        }
    }

}
