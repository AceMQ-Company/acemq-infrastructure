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
package org.acemq.infra.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.Durations;
import org.acemq.infra.config.Endpoint;
import org.acemq.infra.config.EndpointKind;
import org.acemq.infra.config.MirrorSpec;
import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.Operation;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.plan.DefaultSteps;
import org.acemq.infra.plan.ScopeCheck;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.Inventory;

/**
 * The steps a plan describes, carried out.
 *
 * <p>This is the phase where the tool stops being unable to break production, and the shape of the
 * class follows from that one sentence rather than from any idea about how an executor is usually
 * written.
 *
 * <h2>Nothing is attempted until everything has been checked</h2>
 *
 * <p>{@link #preflight()} runs before the first write and refuses the whole run rather than the
 * step. The reason is the one docs/broker-agnostic.md gives about capabilities and it generalises:
 * "the source has rabbitmq_shovel disabled" is worth having at second zero and worth very little at
 * step six with half an estate moved. So a guard with no timeout, a cluster the file names and this
 * run has not got, a drain whose patterns select nothing, an external endpoint in a run with nobody
 * watching — all of them stop the run before anything has changed.
 *
 * <h2>The mode is not something a step can forget</h2>
 *
 * <p>A rehearsal wraps both brokers in {@link Rehearsal} before the first step, so a writing verb
 * reached during one throws rather than writing. A step that checked a flag would eventually be a
 * step that forgot to, and the place that discovers it would be a production cluster.
 *
 * <h2>What it does when it gives up</h2>
 *
 * <p>Aborting after a drain has been declared is the interesting case, because a shovel goes on
 * moving messages after the process that declared it has stopped looking. Leaving it would mean the
 * source quietly empties while the operator reads a report saying the cutover stopped. So the
 * executor tears down the movements <em>it</em> declared, and only those: a shovel somebody else
 * declared is somebody else's, and a tool that tidied up the parameters it found would be deleting
 * a stranger's drain halfway through it. Anything it declared and then could not remove is named in
 * the report under its own heading, because that is the line somebody has to act on.
 */
public final class Executor {

    /** How long between readings. Below the statistics interval there is nothing new to read. */
    private static final Duration POLL = Guards.STATISTICS_INTERVAL;

    /** The timestamp a {@code {{timestamp}}} becomes: sortable, and legal in a filename. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Run run;
    private final Map<String, Broker> brokers = new LinkedHashMap<>();
    private final List<Execution.Taken> taken = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<Broker.Movement> outstanding = new ArrayList<>();
    private final List<Step> completed = new ArrayList<>();

    /**
     * The queues a canary's scope resolved to, and what every guard in one measures.
     *
     * <p>Empty for a whole-estate cutover, where empty means the whole virtual host and that is
     * what the operation means. For a canary it is the difference between a guard that watches the
     * workload being moved and one that watches the estate: a {@code consumers >= 1} on the target
     * that counted the whole vhost would be satisfied by somebody else's application, and an
     * {@code unacked = 0} on the source would wait for workloads this cutover is deliberately
     * leaving alone.
     *
     * <p>Resolved against the source once, and used on both clusters. The target's copies of these
     * queues are made by the topology step, so resolving patterns against the target would give an
     * empty list before that step and a different list after it.
     */
    private final List<String> scopedQueues;

    /** The movement the step being run has just declared, so its guard can watch that one. */
    private Broker.Movement declared;

    /** Whether anybody is watching, asked once: the question itself is noise on a terminal. */
    private Boolean watched;

    private Executor(Run run) {
        this.run = run;
        this.scopedQueues = scopeOf(run);
        for (Run.Side side : List.of(run.from(), run.to())) {
            // The wrap happens here, once, so that every verb every step reaches is already
            // incapable of writing when this is a rehearsal.
            brokers.put(side.name(), run.mode() == Run.Mode.REHEARSE
                    ? new Rehearsal(side.broker()) : side.broker());
        }
    }

    /** A canary's scope, resolved against the source. Nothing for the other two operations. */
    private static List<String> scopeOf(Run run) {
        Deployment deployment = run.file().deployment().orElse(null);
        if (deployment == null
                || deployment.operation().orElse(Operation.BLUE_GREEN) != Operation.CANARY) {
            return List.of();
        }
        return deployment.scope()
                .map(scope -> Selection.select(run.from().probed().inventory().queues(),
                        scope.queues(), Inventory.Queue::name).stream()
                        .map(Inventory.Queue::name).toList())
                .orElse(List.of());
    }

    /**
     * Carries out a run.
     *
     * @param run what to do, and whether it may write
     * @return what happened
     */
    public static Execution execute(Run run) {
        return new Executor(run).go();
    }

    // ---------------------------------------------------------------- the loop

    private Execution go() {
        List<String> refusals = preflight();
        if (!refusals.isEmpty()) {
            refusals.forEach(refusal -> taken.add(new Execution.Taken(0, "refused",
                    Execution.Status.FAILED, List.of(refusal))));
            return finish(Execution.Outcome.REFUSED);
        }

        Map<Step, Integer> numbers = number();
        Execution.Outcome outcome = Execution.Outcome.COMPLETED;
        boolean stopped = false;

        for (Map.Entry<Step, Integer> entry : numbers.entrySet()) {
            if (stopped) {
                taken.add(new Execution.Taken(entry.getValue(), entry.getKey().describeId(),
                        Execution.Status.NOT_REACHED, List.of("the run had already stopped")));
                continue;
            }
            Verdict verdict = step(entry.getKey(), entry.getValue());
            if (verdict != Verdict.CARRY_ON) {
                stopped = true;
                outcome = verdict == Verdict.STOP
                        ? Execution.Outcome.STOPPED : Execution.Outcome.ABORTED;
            }
        }

        if (stopped) {
            standDown();
        }
        return finish(outcome);
    }

    /** What a step leaves the run able to do. */
    private enum Verdict {

        /** It did what it was for. */
        CARRY_ON,

        /** It failed, or a guard expired with {@code abort}. */
        ABORT,

        /** A human said stop, or there was nobody to ask. */
        STOP
    }

    private Execution finish(Execution.Outcome outcome) {
        List<Step> rollback = outcome == Execution.Outcome.REFUSED
                ? List.of()
                : Rollbacks.derive(run.file(), completed, run.from().name(), run.to().name());
        if (!rollback.isEmpty()) {
            notes.addAll(Rollbacks.notes(completed, run.from().name()));
        }
        return new Execution(run.file().metadata().name().orElse("(unnamed)"), run.mode(), outcome,
                taken, notes, outstanding, rollback);
    }

    /**
     * Tears down what this run declared, after it has decided to stop.
     *
     * <p>A drain that is still running after an abort is the failure this exists for: the source
     * goes on emptying while the report says the cutover stopped, and the operator reads the second
     * sentence and not the first. Anything that will not come down is kept in
     * {@link Execution#movements()} by name.
     */
    private void standDown() {
        if (run.mode() == Run.Mode.REHEARSE || outstanding.isEmpty()) {
            return;
        }
        List<Broker.Movement> left = new ArrayList<>();
        for (Broker.Movement movement : outstanding) {
            try {
                brokers.get(movement.on()).cancel(movement);
                notes.add("stopped the movement this run declared: " + movement.describe());
            } catch (RuntimeException failed) {
                left.add(movement);
                notes.add("could not stop " + movement.describe() + ": " + failed.getMessage());
            }
        }
        outstanding.clear();
        outstanding.addAll(left);
    }

    // ---------------------------------------------------------------- preflight

    /**
     * Everything that can be known before the first write, answered before the first write.
     *
     * @return the reasons this run cannot go ahead; empty means it can
     */
    private List<String> preflight() {
        List<String> refusals = new ArrayList<>();
        if (run.file().deployment().isEmpty()) {
            refusals.add("the file has no deployment: block. Validate it first.");
            return refusals;
        }

        refusals.addAll(scopeRefusals());
        refusals.addAll(mirrorRefusals());

        for (Step step : run.steps()) {
            String where = "step " + step.describeId();
            step.find(Action.Requires.class).ifPresent(requires -> {
                for (Capability capability : requires.capabilities()) {
                    // Both clusters, not just the one running the step. A cutover that can roll
                    // back runs every verb in both directions, so a capability the operation
                    // depends on is one both ends need -- and the rollback is the worst possible
                    // moment to discover that only one of them has it.
                    for (Run.Side side : List.of(run.from(), run.to())) {
                        if (!side.probed().can(capability)) {
                            refusals.add(where + " requires " + capability.name() + " and "
                                    + side.name() + " does not have it: "
                                    + side.probed().whyNot(capability)
                                            .orElse("the probe did not establish it") + ".");
                        }
                    }
                }
            });

            step.find(Action.Drain.class).ifPresent(drain -> {
                clusterRefusal(where, drain.from().orElse(run.from().name())).ifPresent(refusals::add);
                clusterRefusal(where, drain.to().orElse(run.to().name())).ifPresent(refusals::add);
                if (queuesToDrain(drain).isEmpty()) {
                    // Not an empty drain that quietly does nothing: a drain that selects nothing
                    // means the patterns and the estate disagree, and carrying on would switch the
                    // endpoint to a cluster with none of the messages on it.
                    refusals.add(where + " is a drain whose queue patterns select nothing on "
                            + drain.from().orElse(run.from().name()) + ". Either the patterns are"
                            + " wrong or the cluster is not what the plan was made against.");
                }
            });

            step.find(Action.Mirror.class).ifPresent(mirror -> {
                if (!mirror.queues().isEmpty()) {
                    refusals.add(where + " asks for queue federation. A federated queue pulls from"
                            + " its upstream only when the upstream has no local consumers, so it"
                            + " is a conditional move rather than a copy — an accidental drain that"
                            + " fires the moment a cutover stops the source's consumers.");
                }
                if (mirror.exchanges().isEmpty()) {
                    refusals.add(where + " is a mirror that names no exchanges, so it would copy"
                            + " nothing.");
                }
            });

            step.find(Action.CopyTopology.class).ifPresent(copy -> {
                clusterRefusal(where, copy.from().orElse(run.from().name())).ifPresent(refusals::add);
                clusterRefusal(where, copy.to().orElse(run.to().name())).ifPresent(refusals::add);
            });

            step.find(Action.CloseConnections.class).ifPresent(close -> {
                clusterRefusal(where, close.on().orElse(run.from().name())).ifPresent(refusals::add);
                close.after().ifPresent(after -> guardRefusal(where + "'s after:",
                        new WaitFor(close.on(), OptionalInt.empty(), after.unacked(),
                                OptionalInt.empty(), Optional.empty(), after.timeout(),
                                after.onTimeout(), after.location())).ifPresent(refusals::add));
            });

            step.find(Action.Switch.class).ifPresent(switched ->
                    refusals.addAll(endpointRefusals(where)));

            step.waitFor().ifPresent(guard -> {
                clusterRefusal(where, guard.on().orElse(run.from().name())).ifPresent(refusals::add);
                guardRefusal(where, guard).ifPresent(refusals::add);
            });
        }
        return refusals;
    }

    /**
     * The check that makes a canary safe, answered before the first write.
     *
     * <p>{@link ScopeCheck} carries the whole argument and it is not restated here. What this adds
     * is the timing, which is the same argument the rest of the preflight makes: "something else is
     * consuming orders.notifications" is worth having at second zero and worth nothing at step six
     * with the producers stopped and the consumers closed. It is checked against what
     * {@code probe()} found rather than against a fresh listing, for the reason
     * {@link #queuesToDrain} gives — a run whose refusals moved underneath it would be a run nobody
     * could review.
     *
     * <p>Also run in a rehearsal. A rehearsal that reported a canary as fine and then had the real
     * run refuse it would be a rehearsal nobody could act on, and the check writes nothing.
     */
    private List<String> scopeRefusals() {
        Deployment deployment = run.file().deployment().orElseThrow();
        if (deployment.operation().orElse(Operation.BLUE_GREEN) != Operation.CANARY) {
            return List.of();
        }
        Optional<Deployment.Scope> scope = deployment.scope();
        if (scope.isEmpty()) {
            return List.of("this is a canary and the file has no deployment.scope block. A canary"
                    + " is a cutover at a smaller scope, and with no scope there is nothing smaller"
                    + " about it: the close step would take every consuming connection on "
                    + run.from().name() + " and the drain would take every queue.");
        }
        ScopeCheck.Result result = ScopeCheck.of(scope.get(), run.from().name(),
                run.from().probed().inventory());
        notes.addAll(result.lines());
        notes.addAll(result.warnings());
        // Said before the run rather than at the step that needs it. docs/canary.md: the endpoint
        // row is the one that surprises people, because a canary needs the switch to move one
        // service and leave everything else where it is -- and in an estate where every
        // application reads the same URL from the same config map, that is the work the canary
        // actually depends on. Discovering it at the endpoint step means discovering it with the
        // producers stopped and the queues already drained.
        notes.add("a canary needs PER-SERVICE routing: "
                + String.join(", ", scope.get().services()) + " must resolve to " + run.to().name()
                + " while everything else still resolves to " + run.from().name()
                + ". The endpoint step is what asks for it, and nothing in this run can check that"
                + " the switch was that narrow.");
        return result.refusals();
    }

    /**
     * What a mirror is not allowed to do, which is most of what a cutover does.
     *
     * <p>docs/canary.md gives the reason a mirror is its own verb rather than a mode of canary: it
     * does not end in a cutover and it has no rollback. It is an observation, and it ends when
     * somebody stops it. Two things follow and both are refusals rather than warnings, because
     * either one turns the observation into the operation the page refuses.
     *
     * <ul>
     *   <li>A <strong>drain</strong> consumes from the source. A mirror that drains has emptied the
     *       cluster it was supposed to leave untouched, and there is no undo for a shovel.</li>
     *   <li>An <strong>endpoint switch</strong> routes clients at a cluster whose consumers are
     *       supposed to be in shadow mode, doing the work and discarding the result. Every message
     *       is then processed by something that throws the answer away and by nothing else.</li>
     * </ul>
     */
    private List<String> mirrorRefusals() {
        Deployment deployment = run.file().deployment().orElseThrow();
        if (deployment.operation().orElse(Operation.BLUE_GREEN) != Operation.MIRROR) {
            return List.of();
        }
        List<String> refusals = new ArrayList<>();
        for (Step step : run.steps()) {
            String where = "step " + step.describeId();
            if (step.has(Action.Drain.class)) {
                refusals.add(where + " drains, and this is a mirror. A drain is a shovel and a"
                        + " shovel consumes: it would empty " + run.from().name() + ", which a"
                        + " mirror exists to leave authoritative and untouched. A mirror copies.");
            }
            if (step.has(Action.Switch.class)) {
                refusals.add(where + " switches the endpoint, and this is a mirror. A mirror has no"
                        + " cutover — " + run.to().name() + "'s consumers are meant to be in shadow"
                        + " mode, doing the work and discarding the result, so routing clients"
                        + " there means every message is processed by something that throws the"
                        + " answer away.");
            }
        }
        return refusals;
    }

    private Optional<String> clusterRefusal(String where, String name) {
        return run.side(name).isPresent() ? Optional.empty()
                : Optional.of(where + " names the cluster '" + name + "', which this run does not"
                        + " have. A run reaches the two clusters deployment.from and deployment.to"
                        + " name and no others.");
    }

    private Optional<String> guardRefusal(String where, WaitFor guard) {
        if (guard.timeout().isEmpty()) {
            // docs/blue-green.md: a guard without a timeout waits forever, and the validator
            // refuses one in a file. Reaching it here means the step was built in code.
            return Optional.of(where + " has a guard with no timeout, which would wait forever.");
        }
        return Guards.tooShortForTheStatistics(guard)
                .map(why -> where + " has a guard this run will not honour: " + why + ".");
    }

    /**
     * Whether the endpoint switch can happen at all in this run.
     *
     * <p>The external case is the one that has to be settled here rather than when the step comes
     * round. An external switch always stops and waits for a human — that is what the word means —
     * so a run with nobody watching cannot complete one, and finding that out at step eight means
     * finding it out with the drain already done and the source already empty. A refusal at second
     * zero costs nothing.
     */
    private List<String> endpointRefusals(String where) {
        Optional<Endpoint> endpoint = run.file().endpoint();
        if (endpoint.isEmpty()) {
            return List.of(where + " switches the endpoint and the file has no endpoint: block, so"
                    + " there is nothing to say how clients reach the other cluster.");
        }
        EndpointKind kind = endpoint.get().kind().orElse(EndpointKind.EXTERNAL);
        if (kind == EndpointKind.HOOK) {
            return endpoint.get().run().isPresent() ? List.of()
                    : List.of(where + " switches the endpoint through a hook and endpoint.run names"
                            + " no command.");
        }
        List<String> refusals = new ArrayList<>();
        if (endpoint.get().description().isEmpty()) {
            // Not documentation: it is the text a human is shown when the run stops. An external
            // endpoint with no description stops a cutover with nothing on the screen to act on.
            refusals.add(where + " is an external endpoint switch with no endpoint.description, so"
                    + " the run would stop and tell nobody what to switch.");
        }
        if (run.mode() == Run.Mode.EXECUTE && unattended()) {
            refusals.add(where + " is an external endpoint switch and there is nobody watching this"
                    + " run. An external switch stops and waits for a human by definition, so this"
                    + " run cannot finish — and discovering that at " + where + " would mean"
                    + " discovering it with the drain already done. Run it from a terminal, or give"
                    + " the file an endpoint hook.");
        }
        return refusals;
    }

    /**
     * Whether there is anybody to ask.
     *
     * <p>Asked by asking, which is the only honest way: a console is an interface and a run cannot
     * know what is behind it. The question is worded so that a console with a person behind it
     * prints something that makes sense on its own.
     */
    private boolean unattended() {
        if (watched == null) {
            watched = run.console().ask("acemq-infra: is anyone watching this run? A step in it"
                    + " will stop and wait for a person.") != Console.Answer.UNATTENDED;
        }
        return !watched;
    }

    // ---------------------------------------------------------------- numbering

    /**
     * The same numbering the plan printed.
     *
     * <p>The backup is a step in the output without being one in the file, and the probe is a step
     * in the file without being one in the output. Both of those are decisions
     * {@link org.acemq.infra.plan.Planner} already made and this has to agree with them, because a
     * report that numbered the steps differently from the plan it came from would be a report
     * nobody could line up against the pull request that approved it.
     */
    private Map<Step, Integer> number() {
        Map<Step, Integer> numbers = new LinkedHashMap<>();
        int next = 1;
        if (takesBackup()) {
            next = backup(next);
        }
        for (Step step : run.steps()) {
            if (step.actions().size() == 1 && step.actions().get(0) instanceof Action.Requires) {
                continue;
            }
            numbers.put(step, next++);
        }
        return numbers;
    }

    private boolean takesBackup() {
        Deployment deployment = run.file().deployment().orElseThrow();
        return deployment.backup().map(backup -> backup.enabled().orElse(true))
                .orElseGet(() -> DefaultSteps.backsUpByDefault(
                        deployment.operation().orElse(Operation.BLUE_GREEN)));
    }

    // ---------------------------------------------------------------- backup

    /**
     * The source's definitions, on disk, before anything is touched.
     *
     * <p>A block in the file rather than an action, and a numbered step here, because it happens at
     * a particular moment and a reader is entitled to know when.
     */
    private int backup(int number) {
        Deployment deployment = run.file().deployment().orElseThrow();
        Deployment.Backup backup = deployment.backup().orElse(new Deployment.Backup(
                Optional.of(true), Optional.empty(), Optional.empty(), deployment.location()));
        boolean redact = backup.redactCredentials().orElse(true);
        Path path = Path.of(backup.path().orElse(DefaultSteps.DEFAULT_BACKUP_PATH)
                .replace("{{name}}", run.file().metadata().name().orElse("deployment"))
                // The planner leaves this as {{timestamp}} on purpose, because a plan that changed
                // every time it was produced could not be diffed. A run is the other way round:
                // two backups of the same estate must not land on the same file.
                .replace("{{timestamp}}", STAMP.format(run.timing().now())));

        List<String> lines = new ArrayList<>();
        lines.add(run.from().name() + " definitions → " + path);
        lines.add(redact ? "credentials redacted"
                : "credentials NOT redacted — the file is a credential, written 0600");

        if (run.mode() == Run.Mode.REHEARSE) {
            taken.add(new Execution.Taken(number, "backup", Execution.Status.REHEARSED, lines));
            return number + 1;
        }

        try {
            Topology topology = brokers.get(run.from().name())
                    .snapshotTopology(new Broker.Scope(List.of(), List.of(TopologyPart.values())));
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            long bytes = topology.writeTo(path, redact);
            lines.add(bytes + " bytes, " + topology.describe());
            taken.add(new Execution.Taken(number, "backup", Execution.Status.DONE, lines));
        } catch (IOException | RuntimeException failed) {
            lines.add("could not be written: " + failed.getMessage());
            taken.add(new Execution.Taken(number, "backup", Execution.Status.FAILED, lines));
            // Deliberately not fatal on its own. The caller decides: go() only stops for a step
            // that returns something other than CARRY_ON, and the backup is numbered outside that
            // loop. A cutover whose backup failed is a cutover somebody should stop, so the note
            // says so in the words an operator needs.
            notes.add("the backup failed and the cutover went ahead anyway. " + run.from().name()
                    + " is about to be emptied and there is now no record of what it was"
                    + " configured to be.");
        }
        return number + 1;
    }

    // ---------------------------------------------------------------- one step

    private Verdict step(Step step, int number) {
        List<String> lines = new ArrayList<>();
        Verdict verdict = Verdict.CARRY_ON;
        declared = null;
        try {
            for (Action action : step.actions()) {
                verdict = act(action, lines);
                if (verdict != Verdict.CARRY_ON) {
                    break;
                }
            }
            if (verdict == Verdict.CARRY_ON && step.waitFor().isPresent()) {
                // A drain's guard measures the queues that drain selected rather than the whole
                // virtual host, and it gets a second fact to check beside the numbers: whether the
                // movement this step declared has torn itself down. A shovel declared with
                // `deleteAfter: queueLength` disappears when it has moved the number of messages
                // the queue held when it started, so its absence is an observation about the
                // movement rather than a number out of the statistics database -- and the whole
                // hazard with a depth guard is that the statistics database is behind. Depth zero
                // and the shovel gone is a great deal more than either on its own.
                List<String> measured = step.find(Action.Drain.class)
                        .map(this::queuesToDrain).orElse(scopedQueues);
                Broker.Movement movement = declared;
                verdict = guard(step.waitFor().get(), lines, measured,
                        () -> movement == null || brokers.get(movement.on()).finished(movement));
            }
        } catch (RuntimeException failure) {
            lines.add("failed: " + failure.getMessage());
            taken.add(new Execution.Taken(number, step.describeId(), Execution.Status.FAILED,
                    lines));
            return Verdict.ABORT;
        }

        Execution.Status status = switch (verdict) {
            case CARRY_ON -> run.mode() == Run.Mode.REHEARSE
                    ? Execution.Status.REHEARSED : Execution.Status.DONE;
            case ABORT, STOP -> Execution.Status.FAILED;
        };
        taken.add(new Execution.Taken(number, step.describeId(), status, lines));
        if (status == Execution.Status.DONE) {
            completed.add(step);
            // A movement that has torn itself down is not something an abort further along has to
            // stand down, and leaving it on the list would mean a later failure reporting a shovel
            // that stopped ten minutes ago as still running. Asked rather than assumed, because a
            // drain step written with no guard under it has been waited on by nobody.
            if (declared != null && brokers.get(declared.on()).finished(declared)) {
                outstanding.remove(declared);
            }
        }
        return verdict;
    }

    private Verdict act(Action action, List<String> lines) {
        if (action instanceof Action.Requires) {
            // Answered by the preflight, before anything was touched. Reaching it here would mean
            // the numbering let a probe step through.
            return Verdict.CARRY_ON;
        }
        if (action instanceof Action.CopyTopology copy) {
            return copyTopology(copy, lines);
        }
        if (action instanceof Action.Announce) {
            return announce(lines);
        }
        if (action instanceof Action.CloseConnections close) {
            return closeConnections(close, lines);
        }
        if (action instanceof Action.Drain drain) {
            return drain(drain, lines);
        }
        if (action instanceof Action.Mirror mirror) {
            return mirror(mirror, lines);
        }
        if (action instanceof Action.Switch switched) {
            return switchEndpoint(switched, lines);
        }
        // The same arrangement the planner uses and for the same reason: pattern matching for
        // switch is not final until 21 and this compiles at 17, so the exhaustiveness the sealing
        // was for is recovered by a test that walks getPermittedSubclasses().
        throw new IllegalStateException("the executor has no answer for a '" + action.keyword()
                + "' step. Every action in the sealed Action type needs one here.");
    }

    // ---------------------------------------------------------------- copyTopology

    private Verdict copyTopology(Action.CopyTopology copy, List<String> lines) {
        String reading = copy.from().orElse(run.from().name());
        String writing = copy.to().orElse(run.to().name());
        List<TopologyPart> parts = included(copy);
        Broker.Scope scope = new Broker.Scope(copy.vhosts(), parts);

        Topology topology = brokers.get(reading).snapshotTopology(scope);
        lines.add(topology.describe() + " from " + reading);
        lines.add(String.join(", ", parts.stream().map(TopologyPart::wire).toList()) + " → "
                + writing);
        if (!copy.exclude().isEmpty()) {
            lines.add(String.join(", ", copy.exclude().stream().map(TopologyPart::wire).toList())
                    + " EXCLUDED at this step");
        }
        if (run.mode() == Run.Mode.REHEARSE) {
            return Verdict.CARRY_ON;
        }
        brokers.get(writing).applyTopology(topology, scope);
        return Verdict.CARRY_ON;
    }

    /** What a copy copies: everything unless the file narrowed it, minus the exclusions. */
    private static List<TopologyPart> included(Action.CopyTopology copy) {
        List<TopologyPart> parts = new ArrayList<>(copy.include().isEmpty()
                ? List.of(TopologyPart.values()) : copy.include());
        parts.removeAll(copy.exclude());
        return parts;
    }

    // ---------------------------------------------------------------- announce

    private Verdict announce(List<String> lines) {
        Optional<Deployment.Announce> envelope = run.file().deployment()
                .orElseThrow().announce();
        if (envelope.isEmpty() || envelope.get().exchange().isEmpty()) {
            // Advisory, and its absence is a fact about the file rather than a failure. Saying so
            // is better than a step that quietly does nothing: the guard immediately after this one
            // is the thing the announcement was meant to give applications a head start on.
            lines.add("deployment.announce names no exchange — nothing is published, and the guard"
                    + " after this one starts with no warning given");
            return Verdict.CARRY_ON;
        }
        Broker.Envelope published = new Broker.Envelope(envelope.get().exchange().get(),
                envelope.get().routingKey().orElse(""), envelope.get().payload().orElse(""));
        lines.add("publish to " + published.exchange() + " / " + published.routingKey() + " on "
                + run.from().name());
        if (run.mode() == Run.Mode.REHEARSE) {
            return Verdict.CARRY_ON;
        }
        if (brokers.get(run.from().name()).announce(published)) {
            lines.add("routed to at least one queue");
        } else {
            lines.add("published and routed NOWHERE — nothing is bound to " + published.exchange()
                    + " for that key, so no application has been told");
            notes.add("the announcement was routed nowhere. Applications got no head start on the"
                    + " guard after it, which usually means the guard is about to spend its whole"
                    + " timeout waiting for producers that were never asked to stop.");
        }
        return Verdict.CARRY_ON;
    }

    // ---------------------------------------------------------------- closeConnections

    private Verdict closeConnections(Action.CloseConnections close, List<String> lines) {
        String on = close.on().orElse(run.from().name());
        List<String> users = close.select().map(Action.CloseConnections.Selector::users)
                .orElse(List.of());
        boolean consumersOnly = close.select().flatMap(Action.CloseConnections.Selector::role)
                .map("consumer"::equals).orElse(false);

        List<Broker.Attachment> matching = brokers.get(on).listAttachments().stream()
                .filter(attachment -> users.isEmpty() || users.contains(attachment.user()))
                .filter(attachment -> !consumersOnly || attachment.consuming())
                .toList();
        lines.add("close " + matching.size() + (consumersOnly ? " consuming" : "") + " connection"
                + (matching.size() == 1 ? "" : "s") + " on " + on
                + (users.isEmpty() ? "" : " (" + String.join(", ", users) + ")"));

        // `after:` is waited on BEFORE the connections close, and the field's name is the one thing
        // in the documented format this had to decide rather than read. docs/blue-green.md numbers
        // step 6 "wait until unacked is zero, then close what is left", and docs/message-state.md
        // is emphatic that closing everything at once maximises the requeue storm, maximises what
        // the shovel then has to move and maximises the duplicate count, all at the moment the
        // estate can least absorb any of them. Read the other way -- close, then wait for unacked
        // to reach zero -- the guard is satisfied the instant it is asked, because closing a
        // connection requeues what it held as *ready* rather than unacknowledged. A condition that
        // is true by construction is not a guard.
        Optional<Action.CloseConnections.After> after = close.after();
        if (after.isPresent()) {
            WaitFor settle = new WaitFor(Optional.of(on), OptionalInt.empty(),
                    after.get().unacked(), OptionalInt.empty(), Optional.empty(),
                    after.get().timeout(), after.get().onTimeout(), after.get().location());
            lines.add("first, the after: condition — nothing is closed until it holds");
            Verdict waited = guard(settle, lines, scopedQueues, () -> true);
            if (waited != Verdict.CARRY_ON) {
                return waited;
            }
        }

        if (run.mode() == Run.Mode.REHEARSE) {
            matching.forEach(attachment -> lines.add("would close " + attachment.name()
                    + " (" + attachment.user() + ")"));
            return Verdict.CARRY_ON;
        }

        String reason = "acemq-infra: " + run.file().metadata().name().orElse("a deployment")
                + " is moving this workload to " + run.to().name();
        for (Broker.Attachment attachment : matching) {
            brokers.get(on).detach(attachment, reason);
        }
        lines.add("closed " + matching.size());
        return Verdict.CARRY_ON;
    }

    // ---------------------------------------------------------------- drain

    private Verdict drain(Action.Drain drain, List<String> lines) {
        String reading = drain.from().orElse(run.from().name());
        String writing = drain.to().orElse(run.to().name());
        List<String> queues = queuesToDrain(drain);

        lines.add("shovel " + reading + " → " + writing + ", " + queues.size() + " queue"
                + (queues.size() == 1 ? "" : "s") + ": " + String.join(", ", queues));
        lines.add("messages LEAVE " + reading + " — after this step the rollback for them is a"
                + " drain in the other direction");

        if (run.mode() == Run.Mode.REHEARSE) {
            return Verdict.CARRY_ON;
        }

        Broker.Movement movement = brokers.get(writing).drain(new Broker.Drainage(
                label("drain", reading, writing), run.side(reading).orElseThrow().amqpUri(),
                run.side(writing).orElseThrow().amqpUri(), queues,
                drain.ackMode().orElse("onConfirm"), drain.deleteAfter().orElse("queueLength")));
        outstanding.add(movement);
        declared = movement;
        lines.add("declared " + movement.describe());
        return Verdict.CARRY_ON;
    }

    /**
     * Which queues a drain actually moves.
     *
     * <p>Resolved against what {@code probe()} found rather than against a fresh listing, and that
     * is the same decision {@link Run.Side} carries: a run that re-read the estate between steps
     * would be a run whose refusals moved underneath it, and the preflight's job is to settle
     * everything before the first write. The cost is that a queue declared after the probe and
     * before the drain is not moved, which is the right way round — a cutover moves the estate
     * somebody reviewed, not the one that appeared while they were reading.
     */
    private List<String> queuesToDrain(Action.Drain drain) {
        String reading = drain.from().orElse(run.from().name());
        List<Inventory.Queue> queues = run.side(reading)
                .map(side -> side.probed().inventory().queues()).orElse(List.of());
        return Selection.select(queues, drain.queues(), Inventory.Queue::name).stream()
                .map(Inventory.Queue::name).toList();
    }

    // ---------------------------------------------------------------- mirror

    private Verdict mirror(Action.Mirror mirror, List<String> lines) {
        String reading = mirror.from().orElse(run.from().name());
        String writing = mirror.to().orElse(run.to().name());
        lines.add("federate " + mirror.exchanges().size() + " exchange"
                + (mirror.exchanges().size() == 1 ? "" : "s") + " " + reading + " → " + writing
                + " (" + String.join(", ", mirror.exchanges()) + ")");
        lines.add("messages are COPIED — " + reading + " keeps them");
        lines.add("EXCHANGE federation. A federated queue would pull only when " + reading
                + " had no local consumers, which is a conditional move rather than a copy");
        // On every mirror step, in a rehearsal as much as in a run, and before the write rather
        // than after it. docs/canary.md names this as the one thing the tool genuinely cannot
        // verify and says it will print it rather than letting it be silent: a mirror whose
        // consumers are not in shadow mode is canary-by-consumer, which the page refuses.
        notes.add(writing + "'s consumers must be in shadow mode — doing the work and discarding"
                + " the result — and NOTHING HERE CAN CHECK THAT. A federated exchange copies"
                + " every message, so anything that writes to a shared database, calls a payment"
                + " provider or sends an email does it twice. It also doubles the traffic and the"
                + " storage on " + writing + ", which on a cluster sized for its normal load is"
                + " the thing that falls over.");

        if (run.mode() == Run.Mode.REHEARSE) {
            return Verdict.CARRY_ON;
        }

        Optional<MirrorSpec.Upstream> upstream = run.file().deployment().orElseThrow().mirror()
                .flatMap(MirrorSpec::upstream);
        Broker.Movement movement = brokers.get(writing).mirror(new Broker.Mirroring(
                label("mirror", reading, writing), run.side(reading).orElseThrow().amqpUri(),
                mirror.exchanges(),
                upstream.map(MirrorSpec.Upstream::prefetch).orElse(OptionalInt.empty()),
                upstream.flatMap(MirrorSpec.Upstream::ackMode)));
        // Not added to `outstanding`: a mirror is the thing the operation exists to leave running,
        // so tearing it down when a later step fails would undo the deployment on its way out.
        lines.add("declared " + movement.describe());
        return Verdict.CARRY_ON;
    }

    // ---------------------------------------------------------------- endpoint

    private Verdict switchEndpoint(Action.Switch switched, List<String> lines) {
        String destination = switched.target().orElse(run.to().name());
        Endpoint endpoint = run.file().endpoint().orElseThrow();
        EndpointKind kind = endpoint.kind().orElse(EndpointKind.EXTERNAL);

        if (kind == EndpointKind.HOOK) {
            List<String> arguments = endpoint.args().stream()
                    .map(argument -> argument.replace("{{target}}", destination)).toList();
            lines.add("HOOK — " + endpoint.run().orElseThrow()
                    + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments)));
            if (run.mode() == Run.Mode.REHEARSE) {
                return Verdict.CARRY_ON;
            }
            return Hooks.run(endpoint.run().orElseThrow(), arguments,
                    endpoint.timeout().orElse(Duration.ofMinutes(5)), lines)
                    ? Verdict.CARRY_ON : Verdict.ABORT;
        }

        lines.add("EXTERNAL — this tool does not own the endpoint");
        lines.add(endpoint.description().orElseThrow());
        if (run.mode() == Run.Mode.REHEARSE) {
            return Verdict.CARRY_ON;
        }
        Console.Answer answer = run.console().ask("Switch the endpoint to " + destination + " now."
                + " " + endpoint.description().orElseThrow() + " — has it been switched?");
        return switch (answer) {
            case PROCEED -> Verdict.CARRY_ON;
            case STOP -> {
                lines.add("stopped: the endpoint was not switched");
                yield Verdict.STOP;
            }
            // The preflight refuses this combination before anything is written, so arriving here
            // means the console changed its mind midway. Stopping is the only answer left.
            case UNATTENDED -> {
                lines.add("stopped: there is nobody to switch the endpoint");
                yield Verdict.STOP;
            }
        };
    }

    // ---------------------------------------------------------------- guards

    /**
     * Waits for a condition, or decides what to do about not having seen it.
     *
     * @param waitFor the guard as the file wrote it
     * @param lines where to record what was seen
     * @param corroboration a second, non-statistical fact that also has to be true. For a drain
     *     this is "the shovel has removed itself", which is worth more than any number the
     *     statistics database can produce
     */
    private Verdict guard(WaitFor waitFor, List<String> lines, List<String> queues,
                          BooleanSupplier corroboration) {
        String on = waitFor.on().orElse(run.from().name());

        if (run.mode() == Run.Mode.REHEARSE) {
            Guards.Answer answer = Guards.check(waitFor, brokers.get(on).measure(queues));
            lines.add("wait on " + on + ": " + answer.describe() + " — right now that is "
                    + answer.verdict().name().toLowerCase(Locale.ROOT)
                    + ", and a cutover would wait "
                    + Durations.format(waitFor.timeout().orElse(Duration.ZERO)) + " for it");
            return Verdict.CARRY_ON;
        }

        Waited waited = await(on, waitFor, queues, corroboration);
        lines.add("wait on " + on + ": " + waited.describe());

        if (waited.outcome() == Waited.Outcome.PASSED) {
            return Verdict.CARRY_ON;
        }
        if (waited.outcome() == Waited.Outcome.UNOBSERVABLE) {
            // onTimeout deliberately does not apply. It answers "the condition did not come true in
            // the time allowed", and nothing here came true or failed to: the reading was never
            // taken. Honouring `continue` on an unobservable condition would walk past a guard
            // nobody ever evaluated, which is the failure this whole verdict exists to prevent.
            lines.add("aborting: a guard whose condition cannot be observed is not a guard that"
                    + " failed, and onTimeout has no answer for it. Fix what is stopping the"
                    + " reading and run it again.");
            return Verdict.ABORT;
        }
        if (waited.outcome() == Waited.Outcome.INTERRUPTED) {
            lines.add("aborting: this process was asked to stop while the guard was waiting");
            return Verdict.ABORT;
        }

        OnTimeout policy = waitFor.onTimeout().orElse(OnTimeout.ABORT);
        return switch (policy) {
            case ABORT -> {
                lines.add("timed out, and onTimeout is abort");
                yield Verdict.ABORT;
            }
            case CONTINUE -> {
                lines.add("timed out, and onTimeout is continue — carrying on");
                notes.add("the guard on " + on + " timed out and the file said continue: "
                        + waited.describe() + ". Whatever it was waiting for was still true when"
                        + " the next step ran.");
                yield Verdict.CARRY_ON;
            }
            case PROMPT -> prompt(on, waited, lines);
        };
    }

    private Verdict prompt(String on, Waited waited, List<String> lines) {
        Console.Answer answer = run.console().ask("The guard on " + on + " timed out: "
                + waited.describe() + ". Carry on anyway?");
        return switch (answer) {
            case PROCEED -> {
                lines.add("timed out, and a human said carry on");
                yield Verdict.CARRY_ON;
            }
            case STOP -> {
                lines.add("timed out, and a human said stop");
                yield Verdict.STOP;
            }
            // The distinction that has to survive into the report. "The operator chose to abort"
            // and "there was no operator" read the same in a log and mean opposite things the
            // morning after, and only one of them says to run it again from a terminal.
            case UNATTENDED -> {
                lines.add("timed out with onTimeout: prompt, and there is nobody watching. A"
                        + " prompt in an unattended run is not a prompt — stopping.");
                yield Verdict.STOP;
            }
        };
    }

    /**
     * The polling loop.
     *
     * <p>Two things here are not obvious and both are the statistics database. The condition has to
     * hold for {@link Run#settle()} rather than once, because the management API's depths refresh
     * on an interval and the first zero a drain produces is very often a zero from before the
     * messages arrived — scripts/blue-green-lab.sh found exactly that and wrote it down. And the
     * run of readings resets the moment the condition stops holding, so a flapping queue cannot
     * accumulate its way past the window.
     */
    private Waited await(String on, WaitFor waitFor, List<String> queues,
                         BooleanSupplier corroboration) {
        Duration timeout = waitFor.timeout().orElse(Duration.ZERO);
        Instant deadline = run.timing().now().plus(timeout);
        Instant satisfiedSince = null;
        String last = "nothing was read";

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                return new Waited(Waited.Outcome.INTERRUPTED, last);
            }
            Guards.Answer answer = Guards.check(waitFor, brokers.get(on).measure(queues));
            last = answer.describe();
            if (answer.verdict() == Guards.Verdict.UNOBSERVABLE) {
                return new Waited(Waited.Outcome.UNOBSERVABLE, last);
            }

            boolean holding = answer.verdict() == Guards.Verdict.SATISFIED
                    && corroboration.getAsBoolean();
            if (holding) {
                if (satisfiedSince == null) {
                    satisfiedSince = run.timing().now();
                }
                Duration held = Duration.between(satisfiedSince, run.timing().now());
                if (held.compareTo(run.settle()) >= 0) {
                    return new Waited(Waited.Outcome.PASSED,
                            last + ", held for " + Durations.format(run.settle()));
                }
            } else {
                satisfiedSince = null;
            }

            if (!run.timing().now().isBefore(deadline)) {
                return new Waited(Waited.Outcome.TIMED_OUT,
                        last + " after " + Durations.format(timeout));
            }
            run.timing().pause(POLL);
        }
    }

    /**
     * What a wait came to.
     *
     * @param outcome how it ended
     * @param describe the readings, for the report
     */
    private record Waited(Outcome outcome, String describe) {

        enum Outcome {
            PASSED, TIMED_OUT, UNOBSERVABLE, INTERRUPTED
        }
    }

    // ---------------------------------------------------------------- odds and ends

    /**
     * What this run calls the things it declares.
     *
     * <p>Prefixed with the deployment's name so that a movement left behind by an abort can be
     * found by somebody who has only the report, and so that two cutovers running against the same
     * pair of clusters do not collide on a parameter name.
     */
    private String label(String what, String reading, String writing) {
        return "acemq-" + run.file().metadata().name().orElse("deployment") + "-" + what + "-"
                + reading + "-to-" + writing;
    }
}
