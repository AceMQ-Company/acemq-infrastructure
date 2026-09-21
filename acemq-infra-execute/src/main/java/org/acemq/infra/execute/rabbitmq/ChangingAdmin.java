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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.acemq.infra.provider.ClusterAccess;
import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.Definitions;
import org.acemq.rabbitmq.admin.ParameterInfo;
import org.acemq.rabbitmq.admin.PolicyInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;

/**
 * A management client that changes things, named so that nobody reaches for it absent-mindedly.
 *
 * <h2>Why this is not {@code ReadOnlyAdmin} with the methods added back</h2>
 *
 * <p>{@code acemq-infra-rabbitmq} holds {@code ReadOnlyAdmin}: a wrapper over
 * {@link RabbitAdmin} with the writing half deliberately left off, so that the probe cannot mutate
 * a cluster it is only supposed to look at. That class is the whole of how "plan writes nothing" is
 * a fact about a classpath rather than a habit, and the obvious way to give the executor what it
 * needs — put {@code declareShovel} and {@code importDefinitions} back on it, perhaps behind a flag
 * — would dismantle it. The probe would then hold an object that <em>can</em> write and merely does
 * not, which is the state of affairs the class was written to end, and every future reader would
 * have to take a comment's word for it.
 *
 * <p>So the writing client is a different class, with a different name that says what it does, in a
 * different module that the probe's module does not depend on and cannot see. The consequences are
 * worth stating because they are the point:
 *
 * <ul>
 *   <li>{@code acemq-infra-rabbitmq} contains no method that writes to a broker. Not "none that are
 *       called" — none that exist. Adding one is a visible change to a module whose description
 *       says it reads and only reads.</li>
 *   <li>Nothing the planner can reach can reach this class. {@code acemq-infra-execute} depends on
 *       {@code acemq-infra-core}; core does not depend on it, and Maven refuses the cycle that
 *       would let it.</li>
 *   <li>The duplication is real and it is two constructors and a handful of delegating methods. It
 *       buys a property that a reviewer can check by looking at a dependency graph rather than by
 *       reading every call site, which is the trade docs/library.md already made when it chose a
 *       reactor over one jar.</li>
 * </ul>
 *
 * <p>This class reads as well as writes, and that is not a weakening of anything: the guarantee
 * being kept is about the <em>probe</em>, and the executor measuring a queue it is about to drain
 * is the executor doing its job.
 */
final class ChangingAdmin implements AutoCloseable {

    /** Where the shovel plugin keeps what it has been told to do. */
    static final String SHOVEL = "shovel";

    /** Likewise for federation. Both are runtime parameters, which is why they are declared alike. */
    static final String FEDERATION_UPSTREAM = "federation-upstream";

    private final RabbitAdmin admin;
    private final ManagementApi api;
    private final String vhost;

    private ChangingAdmin(RabbitAdmin admin, ManagementApi api, String vhost) {
        this.admin = admin;
        this.api = api;
        this.vhost = vhost;
    }

    /**
     * Connects, scoped to the virtual host the deployment file named.
     *
     * @param access where the cluster is and who to be
     * @return a client that can change this cluster
     */
    static ChangingAdmin open(ClusterAccess access) {
        RabbitAdmin connected = RabbitAdmin.connect(access.management(), access.username(),
                access.password(), access.timeout());
        String vhost = access.vhost().isBlank() ? RabbitAdmin.DEFAULT_VHOST : access.vhost();
        return new ChangingAdmin(connected.forVhost(vhost), new ManagementApi(access), vhost);
    }

    /** The virtual host everything here is scoped to. */
    String vhost() {
        return vhost;
    }

    // ---------------------------------------------------------------- reading

    /** The queues in scope, with their depths, their unacked counts and their consumers. */
    List<QueueInfo> queues() {
        return admin.queues();
    }

    /** Every client connection to the cluster. */
    List<ConnectionInfo> connections() {
        return admin.connections();
    }

    /** Every channel, which is how a connection is told it is consuming. */
    List<ChannelInfo> channels() {
        return admin.channels();
    }

    /** The operator policies, which a definitions document does not carry. */
    List<PolicyInfo> operatorPolicies() {
        return admin.operatorPolicies();
    }

    /** The runtime parameters of one component, by name. */
    List<String> parameterNames(String component) {
        return admin.parameters(component).stream().map(ParameterInfo::name).toList();
    }

    /** The policies in scope, by name, which is how a mirror's own policy is recognised again. */
    List<String> policyNames() {
        return admin.policies().stream().map(PolicyInfo::name).toList();
    }

    /**
     * Everything the cluster is configured to be, across every virtual host.
     *
     * <p>The cluster-wide document rather than the vhost-scoped one, and the reason is the import
     * at the far end. {@code /api/definitions/&lt;vhost&gt;} omits the {@code vhost} field from
     * every entry, because the endpoint it came from already said which one — so posting it to
     * {@code /api/definitions}, which is the only import there is, would apply it to the default
     * virtual host. The cluster-wide export carries the field, and
     * {@link RabbitTopology} narrows the document here, where the vhost is known.
     *
     * @return the whole document, held as something only this package can look inside
     */
    Definitions definitions() {
        return admin.exportDefinitions();
    }

    /**
     * How many messages a second are arriving in this virtual host.
     *
     * <p>Empty means the number could not be taken, which is <strong>not</strong> the same as nought
     * and is the distinction the whole {@link org.acemq.infra.execute.Observation} type exists for.
     * A broker with {@code rates_mode = none}, or with the metrics collector switched off, reports
     * no {@code message_stats} at all — and a guard that read that as "nothing is publishing" would
     * pass instantly on the cluster least able to prove it.
     *
     * @return the rate, or empty with the reason it could not be read
     */
    Optional<Double> publishRate() {
        Map<String, Object> vhostDocument = api.get("/api/vhosts/" + ManagementApi.encode(vhost))
                .orElse(Map.of());
        Object stats = vhostDocument.get("message_stats");
        if (!(stats instanceof Map<?, ?> statistics)) {
            return Optional.empty();
        }
        Object details = statistics.get("publish_details");
        if (!(details instanceof Map<?, ?> publish)) {
            // message_stats is there and publish_details is not, which is the broker saying
            // nothing has been published since the collector started counting. That is a nought
            // somebody measured rather than a nought nobody looked for.
            return Optional.of(0.0);
        }
        Object rate = publish.get("rate");
        return rate instanceof Number number ? Optional.of(number.doubleValue()) : Optional.empty();
    }

    // ---------------------------------------------------------------- writing

    /** Applies a definitions document. A merge: nothing absent from it is removed. */
    void importDefinitions(String json) {
        admin.importDefinitions(json);
    }

    /**
     * Writes a policy with its {@code apply-to} chosen rather than assumed.
     *
     * @param name what to call it
     * @param pattern the regular expression the broker matches against names
     * @param definition what the policy sets
     * @param priority higher wins outright among matching policies
     * @param applyTo {@code queues}, {@code exchanges} or {@code all} — and getting this wrong on a
     *     federation policy is how a mirror becomes a drain
     */
    void putPolicy(String name, String pattern, Map<String, Object> definition, int priority,
                   String applyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pattern", pattern);
        body.put("definition", definition);
        body.put("priority", priority);
        body.put("apply-to", applyTo);
        api.put("/api/policies/" + ManagementApi.encode(vhost) + "/" + ManagementApi.encode(name),
                body);
    }

    /** The same, for a policy an application cannot override. */
    void putOperatorPolicy(String name, String pattern, Map<String, Object> definition,
                           int priority, String applyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pattern", pattern);
        body.put("definition", definition);
        body.put("priority", priority);
        body.put("apply-to", applyTo);
        api.put("/api/operator-policies/" + ManagementApi.encode(vhost) + "/"
                + ManagementApi.encode(name), body);
    }

    /** Declares a shovel, or replaces one of the same name. */
    void declareShovel(String name, Map<String, Object> definition) {
        admin.declareShovel(name, definition);
    }

    /** Removes a shovel. It stops moving messages immediately. */
    void deleteShovel(String name) {
        admin.deleteShovel(name);
    }

    /** Declares a federation upstream, which on its own federates nothing until a policy names it. */
    void putFederationUpstream(String name, String uri, Map<String, Object> settings) {
        admin.putFederationUpstream(name, uri, settings);
    }

    /** Removes a federation upstream. Any link using it stops. */
    void deleteFederationUpstream(String name) {
        admin.deleteFederationUpstream(name);
    }

    /** Removes a policy. What it was applying reverts immediately. */
    void deletePolicy(String name) {
        admin.deletePolicy(name);
    }

    /** Closes one connection, telling the client why. */
    void closeConnection(String name, String reason) {
        admin.closeConnection(name, reason);
    }

    /** Publishes one message, for the announcement envelope and nothing else. */
    boolean publish(String exchange, String routingKey, String payload) {
        return api.publish(vhost, exchange, routingKey, payload);
    }

    @Override
    public void close() {
        admin.close();
    }
}
