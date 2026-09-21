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
package org.acemq.infra.plan;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.MirrorSpec;
import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.Operation;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.yaml.Location;

/**
 * The step list each operation gets when the file does not write one.
 *
 * <p>The list is built out of the same {@link Step} and {@link Action} records a file would have
 * parsed into, rather than out of a private vocabulary the planner understands and nothing else
 * does. That is what makes docs/configuration.md's promise true — "plan fills in the default list
 * for the chosen operation, and prints it in full. You can then paste it into the file and edit
 * it" — because the thing printed and the thing a file can say are the same thing. A default list
 * the file could not have written is a hidden behaviour with a printout, which is what the old
 * format's three booleans were.
 *
 * <h2>The order, and why it is not negotiable</h2>
 *
 * <p>docs/blue-green.md numbers the sequence and four of its ten entries are decisions:
 *
 * <ol>
 *   <li><strong>The topology import is split in two, with the drain between the halves.</strong>
 *       A definitions import applies its policies immediately. A {@code message-ttl} or a
 *       {@code max-length} that lands in step 2 is live on an empty cluster long before step 6
 *       starts shovelling a backlog into it, and a forty-minute backlog drained into a queue
 *       enforcing a ten-minute TTL is a forty-minute backlog discarded on arrival. This is the
 *       single reason {@code steps:} replaced three booleans: no arrangement of
 *       {@code useDefinitions}, {@code removeConnections} and {@code createShovels} can express a
 *       step that happens twice, in halves, around a third one.</li>
 *   <li><strong>Producers stop before consumers close.</strong> A consumer closed while producers
 *       are still publishing leaves work behind it, and that work then has to be moved by a
 *       shovel — which republishes, resetting {@code x-delivery-count} and erasing {@code x-death}
 *       on every message it touches. Stop the producers and the consumers drink the queue down
 *       themselves, through the normal path, with their retry and dead-letter behaviour intact.
 *       The inversion is the instinct and it is the expensive order.</li>
 *   <li><strong>Announce is step 4, not step 1.</strong> The announcement is advisory and its only
 *       job is to give applications a head start on the guard immediately after it. Published
 *       before the topology exists, a well-behaved client drains, reconnects to an endpoint still
 *       pointing at the source, and does the whole thing again when the real cutover happens.</li>
 *   <li><strong>Verify is a step.</strong> A closed connection reconnects to wherever the client
 *       resolves the broker address to, so a cutover whose endpoint did not move looks exactly
 *       like one that worked until the next incident.</li>
 * </ol>
 *
 * <p>Getting that order wrong is the bug the file format exists to prevent, so the list is in one
 * place, it is the same list the plan prints, and there is a test that reads it back in order.
 */
public final class DefaultSteps {

    /** What a producer guard waits before giving up, in the absence of anything better. */
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(2);

    /** What a consumer guard waits. Longer, because it is waiting on other people's handlers. */
    private static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(5);

    /** What a drain waits. Longer again, because it is waiting on a backlog. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(15);

    private DefaultSteps() {
    }

    /**
     * The default list for a deployment's operation.
     *
     * @param deployment the deployment block, read for its operation, its two clusters and the
     *     scope or mirror block that narrows them
     * @return the steps, in order
     */
    public static List<Step> of(Deployment deployment) {
        return of(deployment.operation().orElse(Operation.BLUE_GREEN),
                deployment.from().orElse("from"), deployment.to().orElse("to"),
                deployment.scope(), deployment.mirror());
    }

    /**
     * The default list, from the parts of a deployment that shape it.
     *
     * <p>Separate from {@link #of(Deployment)} because the eleven-field record is a great deal of
     * ceremony for a test whose subject is the order of nine steps.
     *
     * @param operation which operation's list
     * @param from the cluster being moved away from
     * @param to the cluster being moved to
     * @param scope what a canary narrows to, if this is one
     * @param mirror what a mirror copies, if this is one
     * @return the steps, in order
     */
    public static List<Step> of(Operation operation, String from, String to,
                                Optional<Deployment.Scope> scope, Optional<MirrorSpec> mirror) {
        return switch (operation) {
            case BLUE_GREEN -> cutover(operation, from, to, Optional.empty());
            case CANARY -> cutover(operation, from, to, scope);
            case MIRROR -> mirror(operation, from, to, mirror);
        };
    }

    /**
     * Whether this operation takes a backup of the source before it starts, when the file has not
     * said either way.
     *
     * <p>A cutover does, because the source is about to be emptied and its definitions document is
     * the only record of what it was. A mirror does not, because nothing leaves the source — the
     * cluster being written to is the target, and backing up the cluster that is not changing is a
     * ritual rather than a precaution.
     *
     * @param operation the operation
     * @return whether a backup belongs in the plan by default
     */
    public static boolean backsUpByDefault(Operation operation) {
        return operation != Operation.MIRROR;
    }

    /** Where {@code plan} writes the backup when the file gave no path. */
    public static final String DEFAULT_BACKUP_PATH = "./backups/{{name}}-{{timestamp}}.json";

    // ---------------------------------------------------------------- blueGreen and canary

    private static List<Step> cutover(Operation operation, String from, String to,
                                      Optional<Deployment.Scope> scope) {
        Location where = origin(operation);
        List<String> vhosts = scope.flatMap(Deployment.Scope::vhost).map(List::of).orElse(List.of());
        List<String> queues = scope.map(Deployment.Scope::queues).orElse(List.of());
        List<String> services = scope.map(Deployment.Scope::services).orElse(List.of());

        return List.of(
                // The capabilities are asserted before anything happens, which is the whole
                // argument of docs/broker-agnostic.md: "blue has rabbitmq_shovel disabled, so
                // drain-messages cannot run" is worth having at second zero and worth very little
                // at step six with half an estate moved.
                step(where, "probe", new Action.Requires(List.of(
                        Capability.TOPOLOGY_EXPORT,
                        Capability.TOPOLOGY_IMPORT_MERGE,
                        Capability.DRAIN_BY_SHOVEL,
                        Capability.CONNECTION_CLOSE,
                        Capability.CONSUMER_INSPECT), where)),

                // The shape, without the rules. The exclusion here is what the step named
                // `policies` at the far end of the drain puts back.
                step(where, "topology", new Action.CopyTopology(Optional.of(from), Optional.of(to),
                        vhosts,
                        List.of(TopologyPart.EXCHANGES, TopologyPart.QUEUES, TopologyPart.BINDINGS,
                                TopologyPart.USERS, TopologyPart.PERMISSIONS,
                                TopologyPart.PARAMETERS),
                        List.of(TopologyPart.POLICIES, TopologyPart.OPERATOR_POLICIES), where)),

                step(where, "announce-drain", new Action.Announce(where)),

                // Producers first. Everything after this is arranged around the fact that a
                // delivery which has not been acknowledged is requeued on the source when its
                // connection closes.
                guard(where, "pause-producers", new WaitFor(Optional.of(from), OptionalInt.of(0),
                        OptionalInt.empty(), OptionalInt.empty(), Optional.empty(),
                        Optional.of(PAUSE_TIMEOUT), Optional.of(OnTimeout.PROMPT), where)),

                new Step(Optional.of("drain-consumers"),
                        List.of(new Action.CloseConnections(Optional.of(from),
                                // No users named unless a canary named its services. An empty
                                // list is every consuming connection, which is what a whole-estate
                                // cutover means and what a canary must not be allowed to mean.
                                Optional.of(new Action.CloseConnections.Selector(services,
                                        Optional.of("consumer"), where)),
                                Optional.of(new Action.CloseConnections.After(OptionalInt.of(0),
                                        Optional.of(SETTLE_TIMEOUT), Optional.of(OnTimeout.ABORT),
                                        where)),
                                where)),
                        Optional.of(new WaitFor(Optional.of(from), OptionalInt.empty(),
                                OptionalInt.of(0), OptionalInt.empty(), Optional.empty(),
                                Optional.of(SETTLE_TIMEOUT), Optional.of(OnTimeout.ABORT), where)),
                        List.of(), where),

                // A shovel. Messages leave the source, which is what makes the rollback a second
                // cutover in the other direction rather than an undo.
                new Step(Optional.of("drain-messages"),
                        List.of(new Action.Drain(Optional.of(from), Optional.of(to), queues,
                                Optional.of("onConfirm"), Optional.of("queueLength"), where)),
                        Optional.of(new WaitFor(Optional.of(from), OptionalInt.empty(),
                                OptionalInt.empty(), OptionalInt.of(0), Optional.empty(),
                                Optional.of(DRAIN_TIMEOUT), Optional.of(OnTimeout.ABORT), where)),
                        List.of(), where),

                // Now the rules, onto a cluster that already holds the backlog.
                step(where, "policies", new Action.CopyTopology(Optional.of(from), Optional.of(to),
                        vhosts, List.of(TopologyPart.POLICIES, TopologyPart.OPERATOR_POLICIES),
                        List.of(), where)),

                step(where, "switch-endpoint", new Action.Switch(Optional.of(to), where)),

                // If the clients came back to the source, the endpoint did not move. Catching that
                // here is minutes; catching it at the next incident is not.
                guard(where, "verify", new WaitFor(Optional.of(to), OptionalInt.empty(),
                        OptionalInt.empty(), OptionalInt.empty(),
                        Optional.of(new WaitFor.Consumers(OptionalInt.of(1), OptionalInt.empty(),
                                where)),
                        Optional.of(SETTLE_TIMEOUT), Optional.of(OnTimeout.ABORT), where)));
    }

    // ---------------------------------------------------------------- mirror

    private static List<Step> mirror(Operation operation, String from, String to,
                                     Optional<MirrorSpec> mirror) {
        Location where = origin(operation);
        List<String> exchanges = mirror.map(MirrorSpec::exchanges).orElse(List.of());

        return List.of(
                step(where, "probe", new Action.Requires(List.of(
                        Capability.TOPOLOGY_EXPORT,
                        Capability.TOPOLOGY_IMPORT_MERGE,
                        Capability.MIRROR_BY_FEDERATION), where)),

                // Everything, policies included, and this is the one place the split does not
                // apply. The split exists to keep a retention policy from eating a backlog that a
                // drain is about to deliver; a mirror never moves a backlog, and a shadow cluster
                // whose policies differ from the source's is not a comparison worth making.
                step(where, "topology", new Action.CopyTopology(Optional.of(from), Optional.of(to),
                        List.of(), List.of(), List.of(), where)),

                step(where, "mirror", new Action.Mirror(Optional.of(from), Optional.of(to),
                        exchanges, List.of(), where)),

                // A federated exchange with nothing consuming the copy is a cluster quietly
                // filling up. This guard is the difference between a mirror and a disk alarm.
                guard(where, "verify", new WaitFor(Optional.of(to), OptionalInt.empty(),
                        OptionalInt.empty(), OptionalInt.empty(),
                        Optional.of(new WaitFor.Consumers(OptionalInt.of(1), OptionalInt.empty(),
                                where)),
                        Optional.of(SETTLE_TIMEOUT), Optional.of(OnTimeout.ABORT), where)));
    }

    // ---------------------------------------------------------------- assembly

    private static Step step(Location where, String id, Action action) {
        return new Step(Optional.of(id), List.of(action), Optional.empty(), List.of(), where);
    }

    private static Step guard(Location where, String id, WaitFor waitFor) {
        return new Step(Optional.of(id), List.of(), Optional.of(waitFor), List.of(), where);
    }

    /**
     * Where a default step came from.
     *
     * <p>A {@link Location} names a file and a line so that an error puts a reader's cursor on the
     * problem. These steps have no file, and saying so in the place the filename goes is better
     * than an empty string that reads like a bug — a finding against a default step should say
     * that the step was filled in, because the fix is to write the step out rather than to go
     * looking for a line that does not exist.
     */
    private static Location origin(Operation operation) {
        return Location.unknown("the default " + operation.wire() + " step list");
    }
}
