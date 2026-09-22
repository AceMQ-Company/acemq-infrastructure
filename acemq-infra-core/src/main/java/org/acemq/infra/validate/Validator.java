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
package org.acemq.infra.validate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.Cluster;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.Endpoint;
import org.acemq.infra.config.EndpointKind;
import org.acemq.infra.config.MirrorSpec;
import org.acemq.infra.config.Operation;
import org.acemq.infra.config.Rollback;
import org.acemq.infra.config.Semantics;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.Streams;
import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.config.UnknownKey;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.yaml.Location;

/**
 * Checks a parsed deployment file without touching a broker.
 *
 * <p>These are scripts/lint-deployment.py's rules, moved into Java. The structural ones at the
 * bottom are the reason the whole class earns its place: they are the mistakes docs/message-state.md
 * is about, expressed as checks, and every one of them costs messages rather than tidiness.
 *
 * <ul>
 *   <li>retention policies copied to the target before the drain, which discards the backlog on
 *       arrival;
 *   <li>a drain with nothing waiting on unacked or the publish rate, which shovels messages that
 *       consumers were still holding;
 *   <li>a mirror built from queue federation, which is an accidental drain that fires at exactly
 *       the moment a cutover stops the source's consumers;
 *   <li>{@code percentage} anywhere in a canary, which is the partitioned-queue bug;
 *   <li>a missing {@code semantics}, which is the tool choosing at-least-once or at-most-once on
 *       the operator's behalf.
 * </ul>
 *
 * <p>A pure function from a parsed file to a report. That is not an accident of implementation: it
 * is what makes the rules testable at all, and what keeps a validator that must run before
 * anything is written from needing something to write to.
 */
public final class Validator {

    /** The one provider that exists. A registry lookup, so it is checked here and not in the parser. */
    private static final Set<String> PROVIDERS = Set.of("rabbitmq");

    /**
     * Every spelling of a traffic split anybody has reached for.
     *
     * <p>A list rather than one key because the word is not the problem — the idea is. Somebody who
     * writes {@code weight: 5} after {@code percentage: 5} is refused has not been helped by the
     * first refusal.
     */
    private static final List<String> SPLIT_KEYS =
            List.of("percentage", "percent", "weight", "split", "trafficSplit");

    private static final String ACTION_KEYWORDS =
            "announce, closeConnections, copyTopology, drain, endpoint, mirror, requires";

    private final DeploymentFile document;
    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> clusterNames;

    private Validator(DeploymentFile document) {
        this.document = document;
        this.clusterNames = document.clusters().keySet();
    }

    /**
     * @param document a file that has already parsed
     * @return everything wrong with it, and everything worth saying about it
     */
    public static ValidationReport validate(DeploymentFile document) {
        Validator validator = new Validator(document);
        validator.run();
        return new ValidationReport(document, List.copyOf(validator.findings));
    }

    private void run() {
        header();
        clusters();
        endpoint();
        deployment();
        rollback();
        streams();
    }

    // ------------------------------------------------------------------ header

    private void header() {
        if (!document.apiVersion().map(DeploymentFile.API_VERSION::equals).orElse(false)) {
            error("apiVersion", "expected '" + DeploymentFile.API_VERSION + "', found "
                    + quoted(document.apiVersion()), document.location());
        }
        if (!document.kind().map(DeploymentFile.KIND::equals).orElse(false)) {
            error("kind", "expected '" + DeploymentFile.KIND + "', found " + quoted(document.kind()),
                    document.location());
        }
        if (document.metadata().name().filter(name -> !name.isBlank()).isEmpty()) {
            error("metadata.name", "is required — it names the plan, the backup file and every "
                    + "log line", document.metadata().location());
        }
        if (document.provider().filter(PROVIDERS::contains).isEmpty()) {
            String providers = PROVIDERS.stream().sorted().collect(Collectors.joining(", "));
            // A registry lookup rather than a closed vocabulary, which is why this is a rule here
            // and not a constant in the parser: docs/broker-agnostic.md describes provider as a
            // short name resolved through a registry, and the registry is what will grow if a
            // second provider is ever written.
            error("provider", document.provider()
                    .map(name -> "'" + name + "' is not one of [" + providers + "]")
                    .orElse("is required; expected one of [" + providers + "]")
                    + ". RabbitMQ is the only provider that exists (docs/broker-agnostic.md)",
                    document.location());
        }
        for (UnknownKey key : document.unknownKeys()) {
            error(key.name(), "unknown top-level key", key.location());
        }
    }

    // ---------------------------------------------------------------- clusters

    private void clusters() {
        if (document.clusters().isEmpty()) {
            error("clusters", "at least one cluster is required", document.location());
            return;
        }
        for (Cluster cluster : document.clusters().values()) {
            String where = "clusters." + cluster.name();
            required(where, "management", cluster.management(), cluster.location());
            required(where, "username", cluster.username(), cluster.location());
            required(where, "password", cluster.password(), cluster.location());

            if (cluster.amqp().isEmpty()) {
                // A warning rather than an error because a file that only copies topology never
                // needs one. Drain and mirror do: a shovel and a federation link are declared
                // through the management API and then dialled by the broker itself, so the URI
                // has to be one the broker can reach rather than one the operator's laptop can.
                warning(where, "no amqp URI; drain and mirror steps need one", cluster.location());
            }
            cluster.tls().ifPresent(tls -> {
                if (tls.verify().filter(verify -> !verify).isPresent()) {
                    warning(where, "tls.verify is false — the management document this reads is a "
                            + "credential", tls.location());
                }
            });
        }
    }

    // ---------------------------------------------------------------- endpoint

    private void endpoint() {
        Optional<Endpoint> endpoint = document.endpoint();
        if (endpoint.isEmpty()) {
            // Only a warning: a mirror never switches an endpoint, and saying so is the block's
            // job rather than a reason to require it.
            warning("endpoint", "absent — a cutover that never moves the endpoint leaves clients "
                    + "on the source", document.location());
            return;
        }
        Endpoint block = endpoint.get();
        Optional<EndpointKind> kind = block.kind();
        if (kind.isEmpty()) {
            error("endpoint.kind", "is required; expected one of [" + EndpointKind.names() + "]",
                    block.location());
            return;
        }
        if (kind.get() == EndpointKind.EXTERNAL && block.description().isEmpty()) {
            error("endpoint", "kind: external must carry a description — it is what a human is "
                    + "shown when the plan stops", block.location());
        }
        if (kind.get() == EndpointKind.HOOK && block.run().isEmpty()) {
            error("endpoint", "kind: hook must carry a run command", block.location());
        }
    }

    // -------------------------------------------------------------- deployment

    private void deployment() {
        Optional<Deployment> maybe = document.deployment();
        if (maybe.isEmpty()) {
            error("deployment", "is required", document.location());
            return;
        }
        Deployment deployment = maybe.get();
        Optional<Operation> operation = deployment.operation();
        if (operation.isEmpty()) {
            error("deployment.operation", "is required; expected one of [" + Operation.names() + "]",
                    deployment.location());
        }

        clusterReference("deployment.from", deployment.from(), deployment.location(), true);
        clusterReference("deployment.to", deployment.to(), deployment.location(), true);

        semantics(deployment, operation);
        canary(deployment, operation);
        mirror(deployment, operation);
        backup(deployment, operation);
        steps(deployment, operation);
    }

    private void semantics(Deployment deployment, Optional<Operation> operation) {
        Optional<Semantics> semantics = deployment.semantics();
        if (operation.filter(Operation.MIRROR::equals).isPresent()) {
            if (semantics.isPresent()) {
                warning("deployment.semantics",
                        "a mirror moves nothing; semantics has no meaning here",
                        deployment.location());
            }
            return;
        }
        if (semantics.isEmpty()) {
            error("deployment.semantics",
                    "is required and has no default. atLeastOnce means a message may be processed "
                            + "on both clusters; atMostOnce means one may be stranded. The tool "
                            + "will not choose this for you (docs/message-state.md)",
                    deployment.location());
        }
    }

    private void canary(Deployment deployment, Optional<Operation> operation) {
        if (operation.filter(Operation.CANARY::equals).isEmpty()) {
            return;
        }
        Optional<Deployment.Scope> scope = deployment.scope();
        if (scope.isEmpty()) {
            error("deployment.scope", "a canary must enumerate its scope — the unit is a queue and "
                    + "everything attached to it", deployment.location());
        } else {
            if (scope.get().queues().isEmpty()) {
                error("deployment.scope.queues", "is required for a canary", scope.get().location());
            }
            if (scope.get().services().isEmpty()) {
                error("deployment.scope.services", "is required for a canary. It is what the "
                        + "consumer check matches on, and that check is what stops a canary from "
                        + "partitioning the queue (docs/canary.md)", scope.get().location());
            }
        }

        scope.ifPresent(one -> closeSelectors(deployment, one));

        // The rule this whole operation exists to enforce. A percentage is not a small cutover for
        // a broker: it is a partitioned queue. Each cluster holds a fraction of the messages, a
        // consumer on one cannot see the other's, per-key ordering is gone, the dead-letter queues
        // are split the same way, and "roll back the 5%" undoes nothing because those messages are
        // already on green. docs/canary.md.
        List<UnknownKey> candidates = new ArrayList<>(deployment.unknownKeys());
        deployment.scope().ifPresent(one -> candidates.addAll(one.unknownKeys()));
        for (UnknownKey key : candidates) {
            if (SPLIT_KEYS.contains(key.name())) {
                error(key.qualified(), "'" + key.name() + "' has no meaning for a broker. "
                        + "Splitting producers by percentage partitions the queue across two "
                        + "clusters; the unit of a broker canary is a whole workload "
                        + "(docs/canary.md)", key.location());
            }
        }
    }

    /**
     * A canary that closes every consuming connection on the source is not a canary.
     *
     * <p>Checkable with no broker in sight, which is why it is here and not only in the live scope
     * check: the close step's selector is written in the same file as the scope, and an empty
     * {@code select.users} means every consuming connection in the virtual host. For a whole-estate
     * cutover that is exactly right and it is what {@code blueGreen} means; for a canary it closes
     * the workloads that are staying, which is the estate-wide outage the operation was chosen to
     * avoid.
     */
    private void closeSelectors(Deployment deployment, Deployment.Scope scope) {
        List<Step> steps = deployment.stepsOrEmpty();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            Optional<Action.CloseConnections> close = step.find(Action.CloseConnections.class);
            if (close.isEmpty()) {
                continue;
            }
            String where = where("deployment.steps", index, step);
            List<String> users = close.get().select()
                    .map(Action.CloseConnections.Selector::users).orElse(List.of());
            if (users.isEmpty()) {
                error(where, "closes connections with no select.users, which is every consuming "
                        + "connection on the cluster. In a canary that closes the workloads that "
                        + "are staying. Name the scope's services: "
                        + String.join(", ", scope.services()), close.get().location());
                continue;
            }
            List<String> strangers = users.stream()
                    .filter(user -> !scope.services().contains(user)).toList();
            if (!strangers.isEmpty() && !scope.services().isEmpty()) {
                // The file disagreeing with itself. Which half is right is not something to guess
                // at: closing a connection the scope never claimed moves a workload nobody planned
                // to move, and leaving it out moves a queue without its consumer.
                error(where, "closes connections for " + String.join(", ", strangers)
                        + ", which deployment.scope.services does not name. A canary moves one "
                        + "workload completely: the services it closes and the services its scope "
                        + "claims have to be the same list (docs/canary.md)",
                        close.get().location());
            }
        }
    }

    private void mirror(Deployment deployment, Optional<Operation> operation) {
        if (operation.filter(Operation.MIRROR::equals).isEmpty()) {
            return;
        }
        Optional<MirrorSpec> spec = deployment.mirror();
        if (spec.isEmpty()) {
            error("deployment.mirror", "is required for a mirror operation", deployment.location());
        } else {
            mirrorTarget("deployment.mirror", spec.get().exchanges(), spec.get().queues(),
                    spec.get().location());
        }

        // A mirror has no cutover, which docs/canary.md gives as the reason it is its own verb
        // rather than a mode of canary. An endpoint switch inside one routes production traffic at
        // a cluster that is receiving a copy and whose consumers are supposed to be discarding
        // their results -- so the messages are processed by shadow consumers and by nobody else.
        List<Step> steps = deployment.stepsOrEmpty();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            String where = where("deployment.steps", index, step);
            step.find(Action.Switch.class).ifPresent(switched ->
                    error(where,
                            "a mirror switches no endpoint. It is an observation: the source stays "
                                    + "authoritative and the target receives a copy its consumers "
                                    + "are meant to discard. Routing clients at the target makes "
                                    + "every message the shadow's to process and nobody else's "
                                    + "(docs/canary.md)", switched.location()));
        }

        if (document.rollback().isPresent()) {
            warning("rollback", "a mirror has no rollback — nothing moved, so there is nothing to "
                    + "undo. Stopping it means removing the federation upstream and the policy "
                    + "that points at it", document.rollback().get().location());
        }
    }

    /**
     * A mirror federates exchanges. Naming queues asks for the wrong mechanism.
     *
     * <p>This is the one rule here that is a correctness finding rather than a transcription of the
     * documented schema, and it is worth keeping the reasoning next to the code that enforces it. A
     * federated <em>queue</em> pulls from its upstream only when the upstream has no local
     * consumers — RabbitMQ's own documentation describes it as letting local consumers receive
     * messages from a remote queue when no local consumers are active there. It is a work-queueing
     * mechanism: a conditional <em>move</em>, not a copy.
     *
     * <p>Which means a mirror built from it fails in the most misleading order available. While the
     * source's consumers are healthy nothing is copied and the target sits empty, so it looks
     * broken and somebody spends an afternoon on it. The moment the source's consumers stop —
     * which is exactly what a cutover does, deliberately — messages start leaving the source. The
     * mirror that was supposed to preserve a rollback has become a drain, and it fired precisely
     * when the rollback was needed.
     *
     * <p>A federated <em>exchange</em> replays what is published upstream into its own bound
     * queues, so the source routes as normal and the target gets its own copy. That is the mirror.
     * docs/message-state.md.
     */
    private void mirrorTarget(String where, List<String> exchanges, List<String> queues,
                              Location location) {
        if (!queues.isEmpty()) {
            error(where, "mirror.queues asks for queue federation, which pulls only when the "
                    + "upstream has no local consumers — a conditional MOVE, not a copy. A mirror "
                    + "federates exchanges: use mirror.exchanges (docs/message-state.md)",
                    location);
        } else if (exchanges.isEmpty()) {
            error(where, "mirror.exchanges is required — a mirror federates exchanges", location);
        }
    }

    private void backup(Deployment deployment, Optional<Operation> operation) {
        Optional<Deployment.Backup> backup = deployment.backup();
        if (backup.isPresent()) {
            boolean enabled = backup.get().enabled().orElse(false);
            boolean redacting = backup.get().redactCredentials().orElse(true);
            if (enabled && !redacting) {
                warning("deployment.backup", "redactCredentials is off. A definitions export "
                        + "carries password hashes; the file it writes is a credential",
                        backup.get().location());
            }
            return;
        }
        if (operation.filter(Operation.MIRROR::equals).isEmpty()) {
            warning("deployment.backup", "absent — nothing captures the source's definitions "
                    + "before the cutover touches anything", deployment.location());
        }
    }

    private void steps(Deployment deployment, Optional<Operation> operation) {
        if (deployment.steps().isEmpty()) {
            warning("deployment.steps", "absent; the default step list for this operation would be "
                    + "used and printed. Fine to start with, worth pinning before a real cutover",
                    deployment.location());
            return;
        }
        List<Step> steps = deployment.steps().get();
        if (steps.isEmpty()) {
            error("deployment.steps", "must be a non-empty list", deployment.location());
            return;
        }
        checkSteps(steps, "deployment.steps", operation);
        ordering(steps);
    }

    // ---------------------------------------------------------------- rollback

    /**
     * The rollback's steps, checked structurally and not for ordering.
     *
     * <p>scripts/lint-deployment.py does not look at the rollback block at all, and this is the one
     * place the Java validator deliberately goes further. A rollback step with an id that names no
     * cluster, or a guard with no timeout, is the same mistake wherever it is written, and finding
     * it at review time rather than during the rollback is the entire point of the tool.
     *
     * <p>The ordering rules are <em>not</em> applied here, and that is not an oversight. A rollback
     * legitimately drains with nothing having waited for the source to settle first: by the time it
     * runs, the cutover has already stopped the producers and closed the consumers, and demanding a
     * second pause step would refuse every correct rollback in examples/.
     */
    private void rollback() {
        Optional<Rollback> maybe = document.rollback();
        if (maybe.isEmpty()) {
            return;
        }
        Rollback rollback = maybe.get();
        clusterReference("rollback.keep", rollback.keep(), rollback.location(), false);
        if (rollback.steps().isPresent()) {
            checkSteps(rollback.steps().get(), "rollback.steps",
                    document.deployment().flatMap(Deployment::operation));
        }
    }

    // ----------------------------------------------------------------- streams

    /**
     * The {@code streams:} block, which is a confirmation and not a setting.
     *
     * <p>Whether a stream is actually in scope is a question about the estate and belongs to the
     * plan — this validator never sees a broker. What it can check is that the block says something
     * coherent, and there is one incoherent thing a file can say: a {@code restartAt} with no
     * {@code acknowledged}. That reads like a setting being applied, and nothing here applies it.
     * An offset is a position in a log and the position a consumer resumes from is the
     * {@code x-stream-offset} its own client asked for; the field is a statement of belief that the
     * plan holds up against the live consumers, and a belief nobody has signed is not a gate.
     */
    private void streams() {
        Optional<Streams> block = document.streams();
        if (block.isEmpty()) {
            return;
        }
        Streams streams = block.get();
        if (streams.acknowledged().isEmpty() && streams.restartAt().isPresent()) {
            error("streams", "restartAt is set and acknowledged is not. restartAt does not move an "
                    + "offset — nothing can — it says what you believe the consumers are configured "
                    + "to do, and the plan checks it against them. The confirmation is "
                    + "acknowledged (docs/message-state.md)", streams.location());
        }
        if (streams.acknowledged().filter(one -> !one).isPresent()) {
            warning("streams", "acknowledged is false, which is the same as not writing the block: "
                    + "a plan with a stream in the drain's scope will be refused until it is true",
                    streams.location());
        }
    }

    // ------------------------------------------------------------------- steps

    private void checkSteps(List<Step> steps, String prefix, Optional<Operation> operation) {
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            checkStep(step, where(prefix, index, step), operation);
            Optional<String> id = step.id();
            if (id.isPresent() && !seen.add(id.get())) {
                error(prefix + "[" + index + "]", "duplicate step id '" + id.get() + "'",
                        step.location());
            }
        }
    }

    private void checkStep(Step step, String where, Optional<Operation> operation) {
        if (step.id().filter(id -> !id.isBlank()).isEmpty()) {
            error(where, "every step needs an id — the plan, the status output and the errors all "
                    + "refer to it", step.location());
        }

        if (step.actions().isEmpty()) {
            // A step whose only content is a waitFor is legitimate: waiting is what it does.
            // `pause-producers` is exactly that — it changes nothing and blocks until the source's
            // publish rate reaches zero.
            if (step.waitFor().isEmpty()) {
                error(where, "no action; expected a waitFor, or one of [" + ACTION_KEYWORDS + "]",
                        step.location());
            }
        } else if (step.actions().size() > 1) {
            String named = step.actions().stream().map(Action::keyword).sorted()
                    .collect(Collectors.joining(", "));
            error(where, step.actions().size() + " actions (" + named
                    + "); a step does exactly one thing", step.location());
        }

        if (!step.unknownKeys().isEmpty()) {
            String named = step.unknownKeys().stream().map(UnknownKey::name).sorted()
                    .collect(Collectors.joining(", "));
            error(where, "unknown keys: " + named, step.unknownKeys().get(0).location());
        }

        for (Action action : step.actions()) {
            checkAction(action, where, operation);
        }

        step.waitFor().ifPresent(guard -> checkGuard(guard, where));
    }

    /**
     * An if-else chain rather than a switch over the sealed interface.
     *
     * <p>Pattern matching for {@code switch} would read better and would give the compiler the
     * exhaustiveness check that is half the reason {@link Action} is sealed, but it was still a
     * preview in Java 17 and this module targets 17 (docs/shape.md). Worth revisiting the day the
     * baseline moves; {@code Requires}, {@code Announce} and {@code CloseConnections} fall through
     * to nothing here and would be visible as such in a switch.
     */
    private void checkAction(Action action, String where, Optional<Operation> operation) {
        if (action instanceof Action.CopyTopology copy) {
            // from and to may be omitted on a topology copy: a file that has said them once at the
            // deployment does not have to repeat them on every step. A drain and a mirror are
            // broker-side operations declared between two named endpoints, so theirs are required.
            endpointReference(where, "copyTopology.from", copy.from(), copy.location(), false);
            endpointReference(where, "copyTopology.to", copy.to(), copy.location(), false);
        } else if (action instanceof Action.Drain drain) {
            endpointReference(where, "drain.from", drain.from(), drain.location(), true);
            endpointReference(where, "drain.to", drain.to(), drain.location(), true);
            if (operation.filter(Operation.MIRROR::equals).isPresent()) {
                error(where, "a mirror must not drain — a drain consumes from the source and a "
                        + "mirror is an observation", drain.location());
            }
        } else if (action instanceof Action.Mirror mirror) {
            endpointReference(where, "mirror.from", mirror.from(), mirror.location(), true);
            endpointReference(where, "mirror.to", mirror.to(), mirror.location(), true);
            mirrorTarget(where, mirror.exchanges(), mirror.queues(), mirror.location());
        } else if (action instanceof Action.Switch target) {
            if (target.target().isPresent() && !clusterNames.contains(target.target().get())) {
                error(where, "endpoint.target: no cluster named '" + target.target().get() + "'",
                        target.location());
            }
        }
        // Requires, Announce and CloseConnections have nothing to check here. Capability names are
        // a closed vocabulary the parser has already resolved; the announcement envelope lives on
        // the deployment; and a connection selector is free-form by design, because
        // docs/configuration.md shows one example of `role` and never enumerates the alternatives.
    }

    private void checkGuard(WaitFor guard, String where) {
        if (guard.on().isPresent() && !clusterNames.contains(guard.on().get())) {
            error(where, "waitFor.on: no cluster named '" + guard.on().get() + "'",
                    guard.location());
        }
        if (guard.timeout().isEmpty()) {
            error(where, "waitFor without a timeout waits forever", guard.location());
        }
    }

    // ---------------------------------------------------------------- ordering

    /**
     * The rules that exist because of docs/message-state.md.
     *
     * <p>Everything above this point is the documented schema, checked. These three are the ones
     * that know something about brokers, and they are the reason a linter was written before the
     * tool it belongs to.
     */
    private void ordering(List<Step> steps) {
        int drainAt = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).has(Action.Drain.class)) {
                drainAt = index;
                break;
            }
        }
        if (drainAt < 0) {
            return;
        }

        // Retention policies landing on the target before the backlog does. An imported
        // definitions document applies its policies immediately, so a message-ttl or a max-length
        // is live the instant it arrives and eats the backlog as the shovel delivers it. The fix
        // is in every worked example: exclude them from the first copy and copy them again after
        // the drain.
        for (int index = 0; index < drainAt; index++) {
            Step step = steps.get(index);
            Optional<Action.CopyTopology> copy = step.find(Action.CopyTopology.class);
            if (copy.isEmpty()) {
                continue;
            }
            Set<TopologyPart> included = Set.copyOf(copy.get().include());
            Set<TopologyPart> excluded = Set.copyOf(copy.get().exclude());
            // Either the include list names them, or there is no include list at all — which means
            // everything — and the exclude list does not take them out again.
            boolean namesThem = included.stream().anyMatch(TopologyPart.RETENTION::contains);
            boolean everything = included.isEmpty() && !excluded.containsAll(TopologyPart.RETENTION);
            if (namesThem || everything) {
                error(where("deployment.steps", index, step),
                        "copies policies to the target before the drain. A message-ttl or "
                                + "max-length policy is live the instant it lands and will discard "
                                + "the backlog on arrival. Exclude policies and operatorPolicies "
                                + "here, and copy them in a step after the drain "
                                + "(docs/message-state.md)",
                        copy.get().location());
            }
        }

        Step drain = steps.get(drainAt);
        String drainWhere = where("deployment.steps", drainAt, drain);

        // A drain with nothing having waited for the source to settle first.
        boolean settled = steps.subList(0, drainAt).stream()
                .anyMatch(step -> step.waitFor().filter(WaitFor::settlesTheSource).isPresent());
        if (!settled) {
            error(drainWhere, "drains with no preceding waitFor on publishRate or unacked. Unacked "
                    + "deliveries requeue on the SOURCE when a connection closes, so draining "
                    + "first shovels messages consumers were still holding (docs/message-state.md)",
                    drain.location());
        }

        // A drain with no guard of its own is a fire-and-forget shovel.
        if (drain.waitFor().isEmpty()) {
            warning(drainWhere, "no waitFor on the drain — nothing confirms the source emptied "
                    + "before the next step runs", drain.location());
        }
    }

    // ----------------------------------------------------------------- helpers

    private void required(String where, String field, Optional<String> value, Location location) {
        if (value.filter(text -> !text.isBlank()).isEmpty()) {
            error(where, field + " is required", location);
        }
    }

    private void clusterReference(String where, Optional<String> name, Location location,
                                  boolean mandatory) {
        if (name.isEmpty()) {
            if (mandatory) {
                error(where, "is required", location);
            }
            return;
        }
        if (!clusterNames.contains(name.get())) {
            error(where, "no cluster named '" + name.get() + "'", location);
        }
    }

    private void endpointReference(String where, String field, Optional<String> name,
                                   Location location, boolean mandatory) {
        if (name.isEmpty()) {
            if (mandatory) {
                error(where, field + " is required", location);
            }
            return;
        }
        if (!clusterNames.contains(name.get())) {
            error(where, field + ": no cluster named '" + name.get() + "'", location);
        }
    }

    private static String where(String prefix, int index, Step step) {
        return step.id().map(id -> prefix + "[" + index + "](" + id + ")")
                .orElse(prefix + "[" + index + "]");
    }

    private static String quoted(Optional<String> value) {
        return value.map(text -> "'" + text + "'").orElse("nothing");
    }

    private void error(String where, String message, Location location) {
        findings.add(Finding.error(where, message, location));
    }

    private void warning(String where, String message, Location location) {
        findings.add(Finding.warning(where, message, location));
    }
}
