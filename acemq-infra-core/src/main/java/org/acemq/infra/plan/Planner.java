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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.Durations;
import org.acemq.infra.config.Endpoint;
import org.acemq.infra.config.EndpointKind;
import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.Operation;
import org.acemq.infra.config.Rollback;
import org.acemq.infra.config.Semantics;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;

/**
 * Configuration plus two probed clusters in; an ordered, diffable plan out.
 *
 * <h2>Why this is a pure function, and how that is enforced rather than promised</h2>
 *
 * <p>The last clause of milestone one — docs/roadmap.md — is that {@code plan} writes nothing to
 * either broker, and the point of the milestone is that clause. A comment saying "be careful not
 * to write anything" is worth nothing at three in the morning six months from now, so the property
 * is arranged to be structural in four ways:
 *
 * <ol>
 *   <li><strong>There is no client here to write through.</strong> This class lives in
 *       {@code acemq-infra-core}, which depends on SnakeYAML and nothing else. The RabbitMQ
 *       provider and its management client are in a module that depends on <em>this</em> one, and
 *       Maven refuses the cycle that would be needed to reverse that. A line of code in this file
 *       cannot name a method that writes to a broker, because no such method is on its classpath.
 *       That is the whole reason docs/library.md chose a reactor over one jar.</li>
 *   <li><strong>The broker is already gone by the time the planner runs.</strong> Its input is a
 *       {@link ProbedCluster}, which is a record: a snapshot taken once, before this is called. A
 *       planner that held a live connection could go back and ask for more, and anything that can
 *       ask can be changed to tell.</li>
 *   <li><strong>The output is the return value.</strong> There is one, it is a {@link Plan}, and
 *       nothing in this class writes a file, opens a socket or reads a clock. A
 *       {@code {{timestamp}}} in a backup path is left as {@code {{timestamp}}} for that reason
 *       as much as for the diffable one.</li>
 *   <li><strong>The tests do not need a broker.</strong> Which is the consequence that keeps the
 *       other three true: a planner that could only be tested against a live cluster would be
 *       tested rarely, and the rare tests would be the ones that shape the code.</li>
 * </ol>
 *
 * <p>The instance fields below are scratch space for one call. The constructor is private and the
 * only way in is {@link #plan(DeploymentFile, ProbedCluster, ProbedCluster)}, so no two calls
 * share anything and the same inputs produce the same string every time.
 */
public final class Planner {

    private final DeploymentFile file;
    private final Deployment deployment;
    private final Operation operation;
    private final ProbedCluster source;
    private final ProbedCluster target;
    private final String from;
    private final String to;
    private final Map<String, ProbedCluster> clusters = new LinkedHashMap<>();

    private final List<PlannedStep> planned = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private final Set<Capability> alreadyRefused = new LinkedHashSet<>();

    /** Facts gathered while rendering, turned into warnings afterwards so the order is fixed. */
    private final List<Inventory.Queue> streamsInScope = new ArrayList<>();
    private long messagesToMove;
    private boolean drains;
    private boolean stepsWereDefaulted;
    private int drainStep;
    private boolean policiesDeferredAndDropped;

    private Planner(DeploymentFile file, ProbedCluster source, ProbedCluster target) {
        this.file = file;
        this.deployment = file.deployment().orElseThrow(() -> new IllegalArgumentException(
                "a file with no deployment: block cannot be planned. Validate it first: the"
                        + " validator says which field is missing and on which line."));
        this.source = source;
        this.target = target;
        this.operation = deployment.operation().orElse(Operation.BLUE_GREEN);
        this.from = deployment.from().orElse(source.name());
        this.to = deployment.to().orElse(target.name());
        // Keyed by the name the file gave them, because that is what every step refers to. The
        // two are put in from-then-to order so that a cluster probed twice under two names -- a
        // file whose from and to are the same cluster, which the validator refuses -- does not
        // silently lose one.
        clusters.put(source.name(), source);
        clusters.put(target.name(), target);
    }

    /**
     * Plans a cutover.
     *
     * @param file a deployment file that has already been validated. Planning an invalid file is
     *     not meaningful: the planner reads fields the validator is responsible for establishing
     *     the presence of
     * @param source what the cluster being moved away from turned out to be
     * @param target what the cluster being moved to turned out to be
     * @return the plan, which may be a refusal
     */
    public static Plan plan(DeploymentFile file, ProbedCluster source, ProbedCluster target) {
        return new Planner(file, source, target).build();
    }

    private Plan build() {
        List<Step> steps = deployment.steps().orElseGet(() -> {
            stepsWereDefaulted = true;
            return DefaultSteps.of(deployment);
        });

        // Numbered before anything is rendered, because the topology step has to be able to say
        // "policies EXCLUDED -- applied at step 7" and it cannot know which step that is until
        // every step has a number. Getting this wrong is not cosmetic: the reader uses that
        // number to check that the rules really do land after the drain.
        Map<Step, Integer> numbers = number(steps);
        for (Map.Entry<Step, Integer> entry : numbers.entrySet()) {
            render(entry.getKey(), entry.getValue(), numbers);
        }

        List<Plan.Requirement> required = requirements(steps);
        gatherWarnings();

        return new Plan(file.metadata().name().orElse("(unnamed)"), headline(), source, target,
                required, planned, warnings, refusals);
    }

    private String headline() {
        StringBuilder line = new StringBuilder(operation.wire())
                .append(", ").append(from).append(" → ").append(to);
        deployment.semantics().map(Semantics::wire)
                .ifPresent(semantics -> line.append(", semantics=").append(semantics));
        return line.toString();
    }

    // ---------------------------------------------------------------- numbering

    /**
     * Assigns each step its number in the plan.
     *
     * <p>Two things move between the file's list and the plan's. The {@code probe} step is not a
     * step in the output — it is the summary block at the top, because a reader wants the two
     * clusters' versions and plugins before the first thing that would touch them. And the backup
     * is a step in the output without being one in the file: it comes from
     * {@code deployment.backup}, which is a block rather than an action, and it still has to
     * appear in the ordered list because it happens at a particular moment and a reader is
     * entitled to know when.
     */
    private Map<Step, Integer> number(List<Step> steps) {
        int next = 1;
        if (takesBackup()) {
            planned.add(backup(next++));
        }
        Map<Step, Integer> numbers = new LinkedHashMap<>();
        for (Step step : steps) {
            if (isProbe(step)) {
                continue;
            }
            numbers.put(step, next++);
        }
        return numbers;
    }

    /**
     * Whether a step is the {@code probe} step.
     *
     * <p>Decided by what it carries rather than by its id, because an id is a label the file's
     * author chose and {@code requires:} is the thing that actually makes a step a probe. A file
     * that calls it {@code preflight} gets the same behaviour.
     */
    private static boolean isProbe(Step step) {
        return step.actions().size() == 1 && step.actions().get(0) instanceof Action.Requires;
    }

    private boolean takesBackup() {
        Optional<Deployment.Backup> backup = deployment.backup();
        if (backup.isPresent()) {
            return backup.get().enabled().orElse(true);
        }
        return DefaultSteps.backsUpByDefault(operation);
    }

    private PlannedStep backup(int number) {
        Deployment.Backup backup = deployment.backup()
                .orElse(new Deployment.Backup(Optional.of(true), Optional.empty(),
                        Optional.empty(), deployment.location()));
        String path = backup.path().orElse(DefaultSteps.DEFAULT_BACKUP_PATH)
                .replace("{{name}}", file.metadata().name().orElse("deployment"));
        List<String> lines = new ArrayList<>();
        lines.add(from + " definitions → " + path);
        if (backup.redactCredentials().orElse(true)) {
            lines.add("credentials redacted");
        } else {
            // docs/message-state.md: a definitions export carries password hashes, and a hash is
            // enough to stand up a broker the real passwords authenticate against.
            lines.add("credentials NOT redacted — the file is a credential, written 0600");
        }
        return new PlannedStep(number, "backup", lines,
                List.of(new PlannedStep.Need(Capability.TOPOLOGY_EXPORT, from)));
    }

    // ---------------------------------------------------------------- the steps

    private void render(Step step, int number, Map<Step, Integer> numbers) {
        List<String> lines = new ArrayList<>();
        List<PlannedStep.Need> needs = new ArrayList<>();
        for (Action action : step.actions()) {
            describe(action, number, numbers, lines, needs);
        }
        step.waitFor().ifPresent(guard -> lines.add(guard(guard)));
        if (lines.isEmpty()) {
            lines.add("nothing to do: no action and no guard");
        }
        planned.add(new PlannedStep(number, step.describeId(), lines, needs));
        checkCapabilities(number, step.describeId(), needs);
    }

    /**
     * Turns one action into the lines the plan prints for it.
     *
     * <p>A chain of {@code instanceof} rather than a switch over the sealed type, and it is worth
     * saying why since {@link Action} was sealed precisely so that a switch would be exhaustive.
     * Pattern matching for {@code switch} is not final until Java 21 and this compiles at 17,
     * which docs/shape.md picked because 17 is what the toolchain matrix starts at. So the
     * exhaustiveness is recovered in a test instead: {@code PlannerTest} walks
     * {@code Action.class.getPermittedSubclasses()} and fails when one of them reaches the throw
     * at the bottom of this method. An eighth action added in phase 3 breaks that test, which is
     * the guarantee the sealing was for.
     */
    private void describe(Action action, int number, Map<Step, Integer> numbers, List<String> lines,
                          List<PlannedStep.Need> needs) {
        if (action instanceof Action.Requires requires) {
            lines.add("requires " + names(requires.capabilities()));
        } else if (action instanceof Action.CopyTopology copy) {
            copyTopology(copy, number, numbers, lines, needs);
        } else if (action instanceof Action.Announce) {
            announce(lines);
        } else if (action instanceof Action.CloseConnections close) {
            closeConnections(close, lines, needs);
        } else if (action instanceof Action.Drain drain) {
            drain(drain, number, lines, needs);
        } else if (action instanceof Action.Mirror mirror) {
            mirror(mirror, lines, needs);
        } else if (action instanceof Action.Switch target) {
            switchEndpoint(target, lines);
        } else {
            throw new IllegalStateException("the planner has no answer for a '" + action.keyword()
                    + "' step. Every action in the sealed Action type needs one here.");
        }
    }

    private void copyTopology(Action.CopyTopology copy, int number, Map<Step, Integer> numbers,
                              List<String> lines, List<PlannedStep.Need> needs) {
        String reading = copy.from().orElse(from);
        String writing = copy.to().orElse(to);
        Inventory inventory = inventoryOf(reading);
        List<TopologyPart> parts = included(copy);

        List<String> counted = new ArrayList<>();
        for (TopologyPart part : parts) {
            countOf(inventory, part).ifPresent(count -> {
                if (count > 0) {
                    counted.add(Text.count(count, singular(part), plural(part)));
                }
            });
        }
        lines.add(counted.isEmpty() ? "nothing in scope on " + reading : String.join(", ", counted));

        if (!copy.exclude().isEmpty()) {
            String excluded = String.join(", ",
                    copy.exclude().stream().map(TopologyPart::wire).toList());
            Optional<Integer> later = laterStepApplying(copy.exclude(), number, numbers);
            if (later.isPresent()) {
                lines.add(excluded + " EXCLUDED — applied at step " + later.get());
            } else {
                // The split with nothing on the far side of it is the failure this arrangement was
                // supposed to prevent, inverted: the backlog survives the drain and then arrives
                // on a cluster that is missing the policies the source had.
                lines.add(excluded + " EXCLUDED — and no later step applies them");
                policiesDeferredAndDropped = true;
            }
        }

        needs.add(new PlannedStep.Need(Capability.TOPOLOGY_EXPORT, reading));
        needs.add(new PlannedStep.Need(Capability.TOPOLOGY_IMPORT_MERGE, writing));
        if (parts.contains(TopologyPart.OPERATOR_POLICIES)) {
            needs.add(new PlannedStep.Need(Capability.OPERATOR_POLICY, writing));
        }
    }

    /** What a copy actually copies: everything, unless the file narrowed it, minus the exclusions. */
    private static List<TopologyPart> included(Action.CopyTopology copy) {
        List<TopologyPart> parts = new ArrayList<>(copy.include().isEmpty()
                ? List.of(TopologyPart.values()) : copy.include());
        parts.removeAll(copy.exclude());
        return parts;
    }

    /**
     * The step that puts back what this one is leaving out.
     *
     * <p>Searched for rather than assumed to be the step named {@code policies}, because the id is
     * the file author's word and the guarantee a reader needs is about the order of the actions,
     * not about a naming convention.
     */
    private Optional<Integer> laterStepApplying(List<TopologyPart> excluded, int number,
                                                Map<Step, Integer> numbers) {
        return numbers.entrySet().stream()
                .filter(entry -> entry.getValue() > number)
                .filter(entry -> entry.getKey().find(Action.CopyTopology.class)
                        .map(copy -> included(copy).containsAll(excluded)).orElse(false))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private void announce(List<String> lines) {
        Optional<Deployment.Announce> envelope = deployment.announce();
        if (envelope.isEmpty()) {
            lines.add("deployment.announce is not set — nothing is published");
            return;
        }
        lines.add("publish to " + envelope.get().exchange().orElse("(no exchange)") + " / "
                + envelope.get().routingKey().orElse("(no routing key)"));
    }

    private void closeConnections(Action.CloseConnections close, List<String> lines,
                                  List<PlannedStep.Need> needs) {
        String on = close.on().orElse(from);
        List<String> users = close.select().map(Action.CloseConnections.Selector::users)
                .orElse(List.of());
        boolean consumersOnly = close.select().flatMap(Action.CloseConnections.Selector::role)
                .map("consumer"::equals).orElse(false);

        long matching = inventoryOf(on).connections().stream()
                .filter(connection -> users.isEmpty() || users.contains(connection.user()))
                .filter(connection -> !consumersOnly || connection.consuming())
                .count();

        StringBuilder line = new StringBuilder("close ")
                .append(Text.count(matching, consumersOnly ? "consuming connection" : "connection",
                        consumersOnly ? "consuming connections" : "connections"))
                .append(" on ").append(on);
        if (!users.isEmpty()) {
            line.append(" (").append(String.join(", ", users)).append(')');
        }
        close.after().ifPresent(after -> after.unacked().ifPresent(
                unacked -> line.append(", after unacked=").append(unacked)));
        lines.add(line.toString());

        close.after().ifPresent(after -> lines.add("wait "
                + after.timeout().map(Durations::format).orElse("(no timeout)")
                + ", on timeout " + timeoutPolicy(after.onTimeout())));

        needs.add(new PlannedStep.Need(Capability.CONNECTION_CLOSE, on));
        if (consumersOnly) {
            // Selecting the consuming connections means being able to see which connections are
            // consuming, which is a permission a read-only monitoring account may not have.
            needs.add(new PlannedStep.Need(Capability.CONSUMER_INSPECT, on));
        }
    }

    private void drain(Action.Drain drain, int number, List<String> lines,
                       List<PlannedStep.Need> needs) {
        String reading = drain.from().orElse(from);
        String writing = drain.to().orElse(to);
        Inventory inventory = inventoryOf(reading);

        List<Inventory.Queue> moving = Patterns.select(inventory.queues(), drain.queues(),
                Inventory.Queue::name);
        List<String> excluded = Patterns.excludedNames(inventory.queues(), drain.queues(),
                Inventory.Queue::name);

        StringBuilder line = new StringBuilder("shovel ").append(reading).append(" → ")
                .append(writing).append(", ").append(Text.count(moving.size(), "queue"));
        if (!excluded.isEmpty()) {
            line.append(" (").append(String.join(", ", excluded)).append(" excluded)");
        }
        lines.add(line.toString());

        long messages = moving.stream().mapToLong(Inventory.Queue::messages).sum();
        lines.add(Text.count(messages, "message") + " to move");

        this.drains = true;
        this.drainStep = number;
        this.messagesToMove += messages;
        moving.stream().filter(Inventory.Queue::isStream).forEach(streamsInScope::add);

        needs.add(new PlannedStep.Need(Capability.DRAIN_BY_SHOVEL, reading));
    }

    private void mirror(Action.Mirror mirror, List<String> lines, List<PlannedStep.Need> needs) {
        String reading = mirror.from().orElse(from);
        String writing = mirror.to().orElse(to);
        StringBuilder line = new StringBuilder("federate ")
                .append(Text.count(mirror.exchanges().size(), "exchange")).append(' ')
                .append(reading).append(" → ").append(writing);
        if (!mirror.exchanges().isEmpty()) {
            line.append(" (").append(String.join(", ", mirror.exchanges())).append(')');
        }
        lines.add(line.toString());
        lines.add("messages are COPIED — " + reading + " keeps them");
        needs.add(new PlannedStep.Need(Capability.MIRROR_BY_FEDERATION, writing));
    }

    private void switchEndpoint(Action.Switch target, List<String> lines) {
        String destination = target.target().orElse(to);
        Optional<Endpoint> endpoint = file.endpoint();
        if (endpoint.isEmpty()) {
            lines.add("no endpoint: block — the plan cannot say how clients reach " + destination);
            return;
        }
        EndpointKind kind = endpoint.get().kind().orElse(EndpointKind.EXTERNAL);
        if (kind == EndpointKind.HOOK) {
            List<String> arguments = endpoint.get().args().stream()
                    .map(argument -> argument.replace("{{target}}", destination)).toList();
            lines.add("HOOK — run " + endpoint.get().run().orElse("(no command)")
                    + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments)));
            endpoint.get().timeout()
                    .ifPresent(timeout -> lines.add("wait " + Durations.format(timeout)));
            return;
        }
        // The one step this tool does not own. docs/blue-green.md: the management API can close a
        // connection and cannot decide where the client reconnects to.
        lines.add("EXTERNAL — will stop and wait:");
        endpoint.get().description().ifPresent(description -> {
            List<String> wrapped = Text.wrap(description, 54);
            for (int index = 0; index < wrapped.size(); index++) {
                lines.add((index == 0 ? "\"" : " ") + wrapped.get(index)
                        + (index == wrapped.size() - 1 ? "\"" : ""));
            }
        });
    }

    /** {@code wait: blue publishRate=0, 2m, on timeout PROMPT}. */
    private String guard(WaitFor waitFor) {
        List<String> conditions = new ArrayList<>();
        condition(waitFor.publishRate(), "publishRate=", conditions);
        condition(waitFor.unacked(), "unacked=", conditions);
        condition(waitFor.depth(), "depth=", conditions);
        waitFor.consumers().ifPresent(consumers -> {
            consumers.min().ifPresent(min -> conditions.add("consumers>=" + min));
            consumers.max().ifPresent(max -> conditions.add("consumers<=" + max));
        });
        StringBuilder line = new StringBuilder("wait: ").append(waitFor.on().orElse(from));
        line.append(conditions.isEmpty() ? " (no condition)" : " " + String.join(", ", conditions));
        waitFor.timeout().ifPresent(timeout -> line.append(", ").append(Durations.format(timeout)));
        line.append(", on timeout ").append(timeoutPolicy(waitFor.onTimeout()));
        return line.toString();
    }

    private static void condition(OptionalInt value, String label, List<String> conditions) {
        value.ifPresent(number -> conditions.add(label + number));
    }

    /**
     * What a guard does when its timer runs out.
     *
     * <p>Printed even when the file left it out, and marked as the default when it did. The choice
     * is meant to be made in the file and agreed beforehand rather than by a tired person watching
     * a timer — so a plan that stayed silent about an unwritten one would be hiding exactly the
     * decision docs/blue-green.md is trying to surface.
     */
    private static String timeoutPolicy(Optional<OnTimeout> onTimeout) {
        return onTimeout.map(policy -> policy.name())
                .orElse(OnTimeout.ABORT.name() + " (default)");
    }

    // ---------------------------------------------------------------- capabilities

    private void checkCapabilities(int number, String id, List<PlannedStep.Need> needs) {
        for (PlannedStep.Need need : needs) {
            ProbedCluster cluster = clusters.get(need.cluster());
            if (cluster == null) {
                refusals.add("step " + number + " " + id + " names the cluster '" + need.cluster()
                        + "', which was not probed. The plan probes the two clusters named by"
                        + " deployment.from and deployment.to.");
                continue;
            }
            if (cluster.can(need.capability())) {
                continue;
            }
            alreadyRefused.add(need.capability());
            refusals.add("step " + number + " " + id + " needs " + need.capability().name()
                    + " on " + need.cluster() + ", which does not have it: "
                    + cluster.whyNot(need.capability()).orElse("the probe did not establish it")
                    + ".");
        }
    }

    /**
     * The file's {@code requires:} list, answered against both clusters.
     *
     * <p>Both, and not just the one that runs the step. A cutover that can roll back runs every
     * verb in both directions — the rollback in docs/blue-green.md drains green back to blue — so
     * a capability the operation depends on is a capability both ends need, and a plan that passed
     * because one cluster had each half of the pair would be a plan that fails on the rollback,
     * which is the worst moment available to discover it.
     */
    private List<Plan.Requirement> requirements(List<Step> steps) {
        List<Plan.Requirement> answered = new ArrayList<>();
        for (Step step : steps) {
            step.find(Action.Requires.class).ifPresent(requires -> {
                for (Capability capability : requires.capabilities()) {
                    boolean met = source.can(capability) && target.can(capability);
                    answered.add(new Plan.Requirement(capability, met));
                    if (!met && alreadyRefused.add(capability)) {
                        ProbedCluster without = source.can(capability) ? target : source;
                        refusals.add("the file requires " + capability.name() + " and "
                                + without.name() + " does not have it: "
                                + without.whyNot(capability).orElse("it was not established")
                                + ".");
                    }
                }
            });
        }
        return answered;
    }

    // ---------------------------------------------------------------- warnings

    /**
     * The things that are true, that the plan is going to do anyway, and that somebody is going to
     * be surprised by if they are not written down.
     *
     * <p>Assembled here rather than as they are discovered, so that the order is a property of
     * this method rather than of the order the steps happened to be rendered in. The first three
     * are the ones docs/roadmap.md puts in the worked example, and they are in that order because
     * it is the order of how little can be done about them.
     */
    private void gatherWarnings() {
        if (!streamsInScope.isEmpty()) {
            String names = String.join(", ", streamsInScope.stream()
                    .map(Inventory.Queue::name).toList());
            String acknowledged = file.streams().flatMap(streams -> streams.acknowledged())
                    .orElse(false)
                    ? "streams.acknowledged is set, so the plan proceeds"
                    : "streams.acknowledged is NOT set";
            warnings.add(Text.count(streamsInScope.size(), "stream") + " in scope (" + names
                    + "). Offsets do not travel between clusters and no API at any version can"
                    + " write one into another cluster's log, so every consumer restarts at"
                    + " whatever its x-stream-offset says. " + acknowledged + ".");
            if (!file.streams().flatMap(streams -> streams.acknowledged()).orElse(false)) {
                // docs/message-state.md: the tool refuses to plan a stream step silently. This is
                // the refusal, and the reason it is a refusal rather than a warning is that the
                // consequence -- a week of reprocessing, or a silent gap -- is not visible in the
                // estate afterwards.
                refusals.add("a stream is in the drain's scope and streams.acknowledged is not"
                        + " set. Stream offsets cannot be moved between clusters; the file has to"
                        + " say, in writing, that the consumers' restart position is accepted.");
            }
        }

        if (drains && messagesToMove > 0) {
            warnings.add("a shovel republishes: x-delivery-count resets and x-death is erased on"
                    + " all " + Text.count(messagesToMove, "message") + ". A quorum-queue message"
                    + " one delivery from being dead-lettered arrives with its five attempts back.");
        }

        file.rollback().ifPresent(this::rollbackWarning);

        if (operation == Operation.MIRROR) {
            warnings.add("green's consumers must be in shadow mode. A federated exchange copies"
                    + " every message, so anything that writes to a shared database or calls a"
                    + " payment provider will do it twice, and the tool cannot check this.");
        }

        if (deployment.semantics().map(semantics -> semantics == Semantics.AT_MOST_ONCE)
                .orElse(false)) {
            warnings.add("semantics=atMostOnce: a message may be stranded on " + from + " and"
                    + " never processed. Nothing in this plan will tell you which ones.");
        }

        if (operation == Operation.CANARY) {
            warnings.add("a canary is safe only when every consumer of the scoped queues belongs"
                    + " to one of the named services. Checking that against live consumers is"
                    + " phase 3; this plan does not do it for you.");
        }

        if (policiesDeferredAndDropped) {
            warnings.add("a topology copy excludes parts that no later step applies. Whatever was"
                    + " excluded never reaches " + to + ", which means the target ends the cutover"
                    + " configured differently from the source it replaced.");
        }

        if (stepsWereDefaulted) {
            warnings.add("the file has no steps: list, so the default " + operation.wire()
                    + " list above was filled in. Paste it into the file to pin it: a default that"
                    + " changes with a release is a cutover that changes with a release.");
        }
    }

    private void rollbackWarning(Rollback rollback) {
        Optional<Action.Drain> back = rollback.stepsOrEmpty().stream()
                .flatMap(step -> step.find(Action.Drain.class).stream()).findFirst();
        if (back.isEmpty()) {
            return;
        }
        String reading = back.get().from().orElse(to);
        String writing = back.get().to().orElse(from);
        StringBuilder warning = new StringBuilder("rollback drains ").append(reading)
                .append(" → ").append(writing).append('.');
        if (drains) {
            // The failure mode docs/blue-green.md calls out by name: blue is still there, still
            // configured, still accepting connections -- and empty, so switching the endpoint back
            // on its own gives you a cluster with nothing in it.
            warning.append(' ').append(writing).append("'s queues will be empty after step ")
                    .append(drainStep).append('.');
        }
        warning.append(" Anything that comes back has been republished twice, so whatever a shovel"
                + " does to a header has been done to it twice.");
        warnings.add(warning.toString());
    }

    // ---------------------------------------------------------------- odds and ends

    private Inventory inventoryOf(String cluster) {
        ProbedCluster probed = clusters.get(cluster);
        return probed == null ? Inventory.empty() : probed.inventory();
    }

    private static String names(List<Capability> capabilities) {
        return String.join(", ", capabilities.stream().map(Capability::name).toList());
    }

    private static Optional<Integer> countOf(Inventory inventory, TopologyPart part) {
        return switch (part) {
            case EXCHANGES -> Optional.of(inventory.exchanges());
            case QUEUES -> Optional.of(inventory.queues().size());
            case BINDINGS -> Optional.of(inventory.bindings());
            case USERS -> Optional.of(inventory.users());
            case PERMISSIONS -> Optional.of(inventory.permissions());
            case PARAMETERS -> Optional.of(inventory.parameters());
            case POLICIES -> Optional.of(inventory.policies());
            case OPERATOR_POLICIES -> Optional.of(inventory.operatorPolicies());
            // A plan is scoped to a virtual host, so counting virtual hosts would be counting the
            // thing the scope already fixed.
            case VHOSTS -> Optional.empty();
        };
    }

    private static String singular(TopologyPart part) {
        return switch (part) {
            case EXCHANGES -> "exchange";
            case QUEUES -> "queue";
            case BINDINGS -> "binding";
            case USERS -> "user";
            case PERMISSIONS -> "permission";
            case PARAMETERS -> "parameter";
            case POLICIES -> "policy";
            case OPERATOR_POLICIES -> "operator policy";
            case VHOSTS -> "vhost";
        };
    }

    private static String plural(TopologyPart part) {
        return switch (part) {
            case POLICIES -> "policies";
            case OPERATOR_POLICIES -> "operator policies";
            default -> singular(part) + "s";
        };
    }
}
