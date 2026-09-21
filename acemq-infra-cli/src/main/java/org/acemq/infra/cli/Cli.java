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
package org.acemq.infra.cli;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.acemq.infra.config.Cluster;
import org.acemq.infra.config.ConfigException;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFiles;
import org.acemq.infra.config.Environment;
import org.acemq.infra.execute.Broker;
import org.acemq.infra.execute.Console;
import org.acemq.infra.execute.Execution;
import org.acemq.infra.execute.Executor;
import org.acemq.infra.execute.Run;
import org.acemq.infra.plan.Plan;
import org.acemq.infra.plan.Planner;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.Prober;
import org.acemq.infra.validate.Finding;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;

/**
 * {@code acemq-infra validate}, {@code plan} and {@code apply}, and nothing else.
 *
 * <p>Phase 1 stopped at the first two, because the whole of that milestone was that nothing here
 * could write to a broker. Phase 2 adds the third, and the shape of it follows from the fact that
 * this is now the half of the tool that can break production.
 *
 * <h2>What {@code apply} with no flags does</h2>
 *
 * <p>It prints the plan it is about to carry out, against the two clusters as they are at this
 * moment, and then it stops and asks. Nothing has been written when the question is asked, and the
 * only thing that gets past it is somebody typing the word {@code yes} at a terminal. That is the
 * behaviour a person who is half awake at three in the morning gets, and it is the behaviour they
 * should get: the command that empties a cluster is not a command that should run because a
 * shell-history entry was one arrow key away.
 *
 * <p>{@code --yes} is how a pipeline says it has already decided, and it is worth being exact about
 * what it does <em>not</em> buy. It consents to the run starting. It does not answer any question
 * the run asks afterwards — a guard whose {@code onTimeout} is {@code prompt}, or an
 * {@code endpoint: external} switch. Those are answered by {@link Terminal}, or by
 * {@link Console#unattended} when there is no terminal, and no argument on this command line
 * constructs either one. So {@code --yes} in a pipeline gets a run that refuses an external endpoint
 * switch in preflight, before the first write, which is the executor and this command agreeing
 * rather than each having an opinion.
 *
 * <h2>What {@code --dry-run} is, and what it is not</h2>
 *
 * <p>It is not the plan printed again. {@code plan} reports what a cutover would do given two probe
 * snapshots; {@code --dry-run} re-probes both clusters, then rehearses every step against them —
 * reading the topology, listing the connections, measuring the queues — and reports what each step
 * <em>would do at this moment</em>. A guard says what its condition is right now rather than what it
 * will wait for. The mode is {@code Run…rehearsal()}, so both brokers are wrapped in a decorator
 * whose writing verbs throw: a step that forgot which mode it was in would fail loudly here rather
 * than quietly write to a production cluster.
 *
 * <p>Everything that varies is a constructor argument — the two streams, the environment, the one
 * verb that reaches a broker, the one that can change one, and the human — so every command can be
 * run end to end in a test against constructed clusters and the output compared, rather than
 * eyeballed once by whoever wrote it.
 */
public final class Cli {

    /** The file was read, the rules hold, and any plan produced can run. */
    public static final int OK = 0;

    /** The file has errors, the plan is refused, or a run was refused, stopped or aborted. */
    public static final int FINDINGS = 1;

    /** The command line itself was wrong, or the file could not be read at all. */
    public static final int USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;
    private final Environment environment;
    private final Prober prober;
    private final Brokers brokers;
    private final Console console;

    /**
     * @param out where output goes
     * @param err where complaints about the command line go
     * @param environment where {@code ${VAR}} is looked up
     * @param prober the one thing here that reaches a broker
     * @param brokers the one thing here that can change one
     * @param console the human, if there is one. Whether there is one is decided by
     *     {@link Terminal} from the process, never from an argument
     */
    public Cli(PrintStream out, PrintStream err, Environment environment, Prober prober,
               Brokers brokers, Console console) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.prober = prober;
        this.brokers = brokers;
        this.console = console;
    }

    /**
     * Runs one command.
     *
     * @param arguments the command line, without the program name
     * @return the exit code
     */
    public int run(String... arguments) {
        List<String> args = List.of(arguments);
        if (args.isEmpty()) {
            usage(err);
            return USAGE;
        }
        String command = args.get(0);
        if (command.equals("-h") || command.equals("--help") || command.equals("help")) {
            usage(out);
            return OK;
        }
        if (command.equals("--version")) {
            out.println("acemq-infra " + version());
            return OK;
        }

        Options options;
        try {
            options = Options.of(args.subList(1, args.size()));
        } catch (IllegalArgumentException wrong) {
            err.println("acemq-infra: " + wrong.getMessage());
            usage(err);
            return USAGE;
        }

        switch (command) {
            case "validate":
                return misplaced(options, "validate") ? USAGE : validate(options);
            case "plan":
                if (options.requireVariables()) {
                    // The flag exists to make validate behave as plan already does. Accepting it
                    // on plan would suggest plan has another mode, and it does not.
                    err.println("acemq-infra: --require-variables is a validate option. plan"
                            + " always requires them: it is about to authenticate.");
                    return USAGE;
                }
                return misplaced(options, "plan") ? USAGE : plan(options);
            case "apply":
                return applyOptions(options) ? apply(options) : USAGE;
            default:
                err.println("acemq-infra: there is no '" + command + "' command.");
                usage(err);
                return USAGE;
        }
    }

    // ---------------------------------------------------------------- the flags each command has

    /**
     * Whether a reading command was given one of {@code apply}'s flags.
     *
     * <p>Refused rather than ignored. Both of these mean something specific about writing, and a
     * command that accepted them quietly would be a command somebody believes they have dry-run.
     */
    private boolean misplaced(Options options, String command) {
        if (options.dryRun()) {
            err.println("acemq-infra: --dry-run is an apply option. " + command + " writes nothing"
                    + " to either broker, so there is no wet run for it to be the dry one of.");
            return true;
        }
        if (options.yes()) {
            err.println("acemq-infra: --yes is an apply option. " + command + " changes nothing,"
                    + " so there is nothing here to agree to.");
            return true;
        }
        return false;
    }

    /** Whether {@code apply}'s command line makes sense. */
    private boolean applyOptions(Options options) {
        if (options.requireVariables()) {
            err.println("acemq-infra: --require-variables is a validate option. apply always"
                    + " requires them: it is about to authenticate.");
            return false;
        }
        if (options.dryRun() && options.yes()) {
            // Refused rather than quietly ignored, because the two words together describe a
            // situation that does not exist, and the reader has plainly got one of them wrong.
            // Guessing which would be guessing whether they meant to write to a broker.
            err.println("acemq-infra: --yes and --dry-run together. A dry run writes nothing, so"
                    + " there is nothing to agree to — drop one of them and say which you meant.");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- validate

    private int validate(Options options) {
        // Unset variables are answered with their own reference text, and the names are kept so
        // that the report can say which. Variables is where that decision is argued.
        Variables variables = Variables.recording(environment);
        Optional<DeploymentFile> file = read(options.file(),
                options.requireVariables() ? environment : variables);
        if (file.isEmpty()) {
            return USAGE;
        }

        ValidationReport report = Validator.validate(file.get());
        out.println(options.file() + ": " + summary(report));
        report.findings().forEach(finding -> out.println("  " + prefix(finding) + finding));
        unsetVariables(variables);
        return report.ok() ? OK : FINDINGS;
    }

    private String summary(ValidationReport report) {
        if (report.ok()) {
            return "ok — " + report.summary()
                    + (report.warnings().isEmpty() ? ""
                            : ", " + report.warnings().size() + " warning"
                                    + (report.warnings().size() == 1 ? "" : "s"));
        }
        return report.errors().size() + " error" + (report.errors().size() == 1 ? "" : "s")
                + ", " + report.warnings().size() + " warning"
                + (report.warnings().size() == 1 ? "" : "s");
    }

    private static String prefix(Finding finding) {
        return finding.isError() ? "error: " : "warning: ";
    }

    private void unsetVariables(Variables variables) {
        if (variables.unset().isEmpty()) {
            return;
        }
        out.println("  warning: " + variables.unset().size() + " variable"
                + (variables.unset().size() == 1 ? " is" : "s are") + " not set here and "
                + (variables.unset().size() == 1 ? "was" : "were") + " left as written: "
                + String.join(", ", variables.unset()));
        out.println("           validate reads the file and never connects, so this checks its"
                + " structure.");
        out.println("           plan needs them set: it authenticates. --require-variables makes"
                + " this an error.");
    }

    // ---------------------------------------------------------------- plan

    private int plan(Options options) {
        Ready ready = probe(options);
        if (ready.probed().isEmpty()) {
            return ready.code();
        }
        Probed probed = ready.probed().get();
        Plan plan = Planner.plan(probed.file(), probed.source().probed(), probed.target().probed());
        out.print(plan.render());
        return plan.ok() ? OK : FINDINGS;
    }

    // ---------------------------------------------------------------- apply

    private int apply(Options options) {
        Ready ready = probe(options);
        if (ready.probed().isEmpty()) {
            return ready.code();
        }
        Probed probed = ready.probed().get();
        if (!dialable(probed.source()) || !dialable(probed.target())) {
            return USAGE;
        }

        // The plan is produced even for a dry run, and its refusals are honoured either way. It
        // knows things the executor's preflight does not -- a stream in the drain's scope with no
        // streams.acknowledged is the one that costs a week of reprocessing -- and a refused plan
        // is not something to hand to an executor and hope.
        Plan plan = Planner.plan(probed.file(), probed.source().probed(), probed.target().probed());
        if (!plan.ok()) {
            out.print(plan.render());
            err.println("acemq-infra: the plan is refused, so nothing was run.");
            return FINDINGS;
        }
        if (!options.dryRun()) {
            // Printed before the question and not after it: the gate is worth nothing if what is
            // being agreed to is off the top of the screen.
            out.print(plan.render());
            if (!confirmed(probed, options)) {
                return FINDINGS;
            }
        }

        try (Broker from = brokers.open(probed.source().access());
                Broker to = brokers.open(probed.target().access())) {
            Run.Builder builder = Run.of(probed.file())
                    .from(probed.source().side(from))
                    .to(probed.target().side(to))
                    .console(console);
            // Two terminal methods with two different names rather than a flag, which is Run's own
            // design and the reason this line is the only place in the CLI that decides. A boolean
            // threaded through here would be one `!` away from a cutover somebody asked to rehearse.
            Execution execution = Executor.execute(
                    options.dryRun() ? builder.rehearsal() : builder.cutover());
            out.print(execution.render());
            return execution.ok() ? OK : FINDINGS;
        } catch (RuntimeException unreachable) {
            // A connection that could not be opened at all, which is the one failure that happens
            // before the executor has a chance to refuse anything.
            err.println("acemq-infra: " + unreachable.getMessage());
            return FINDINGS;
        }
    }

    /**
     * The gate in front of the first write.
     *
     * @return whether to go ahead
     */
    private boolean confirmed(Probed probed, Options options) {
        String name = probed.file().metadata().name().orElse("this deployment");
        if (options.yes()) {
            out.println("--yes was given: " + name + " starts without asking. Questions the run"
                    + " asks after this point are still answered by whoever is watching it, and by"
                    + " nobody when that is nobody.");
            return true;
        }

        Console.Answer answer = console.ask("acemq-infra: about to run " + name + ". Messages leave "
                + probed.source().access().name() + " — after the drain the rollback for them is a"
                + " second cutover in the other direction, not a switch back.");
        switch (answer) {
            case PROCEED:
                return true;
            case STOP:
                err.println("acemq-infra: not confirmed. Nothing was written.");
                return false;
            case UNATTENDED:
            default:
                // The default that a pipeline gets, and it is a refusal rather than a prompt
                // answered on somebody's behalf. Naming the flag is the whole of the message: a
                // pipeline that means it says so once, in a file somebody reviewed.
                err.println("acemq-infra: apply stops and asks before it writes anything, and there"
                        + " is no terminal here to ask. Run it from one, or pass --yes if this"
                        + " pipeline has already decided. Nothing was written.");
                return false;
        }
    }

    // ---------------------------------------------------------------- reading and probing

    /**
     * Everything {@code plan} and {@code apply} both need: a validated file and two live clusters.
     *
     * <p>One method because the two commands have to agree about all of it. An {@code apply} that
     * validated less than {@code plan} did, or resolved a cluster differently, would be an
     * {@code apply} that ran a file the reviewed plan was never made from.
     *
     * @return the file and both sides, or the exit code of whatever stopped it
     */
    private Ready probe(Options options) {
        Optional<DeploymentFile> read = read(options.file(), environment);
        if (read.isEmpty()) {
            return Ready.no(USAGE);
        }
        DeploymentFile file = read.get();

        // Validated before a single connection is opened. A file with a drain in the wrong place
        // should be refused by the thing that can see it is in the wrong place, not by a broker
        // that will do exactly what it is told.
        ValidationReport report = Validator.validate(file);
        if (!report.ok()) {
            err.println(options.file() + ": " + summary(report) + ". Not run.");
            report.errors().forEach(finding -> err.println("  error: " + finding));
            return Ready.no(FINDINGS);
        }
        report.warnings().forEach(finding -> err.println("  warning: " + finding));

        Deployment deployment = file.deployment().orElseThrow();
        Optional<Side> source = resolve(file, deployment.from());
        Optional<Side> target = resolve(file, deployment.to());
        if (source.isEmpty() || target.isEmpty()) {
            return Ready.no(USAGE);
        }

        try {
            return Ready.of(new Probed(file, source.get().probedBy(prober),
                    target.get().probedBy(prober)));
        } catch (RuntimeException unreachable) {
            err.println("acemq-infra: " + unreachable.getMessage());
            return Ready.no(FINDINGS);
        }
    }

    /**
     * Turns a named cluster in the file into the things a provider and an executor need.
     *
     * <p>The translation is here rather than in the provider because the provider package is not
     * allowed to know a deployment file exists — docs/library.md — and this is the seam where that
     * is paid for. It buys a probe and an executor that a Kubernetes secret or a test can drive
     * with no YAML document in the picture.
     */
    private Optional<Side> resolve(DeploymentFile file, Optional<String> name) {
        Cluster cluster = file.clusters().get(name.orElse(""));
        if (cluster == null) {
            err.println("acemq-infra: no cluster named '" + name.orElse("") + "' in the file.");
            return Optional.empty();
        }
        if (cluster.management().isEmpty() || cluster.username().isEmpty()
                || cluster.password().isEmpty()) {
            err.println("acemq-infra: cluster '" + cluster.name() + "' has no management URL or no"
                    + " credentials, so it cannot be probed.");
            return Optional.empty();
        }
        return Optional.of(new Side(ClusterAccess.to(cluster.name(), cluster.management().get(),
                cluster.vhost().orElse("/"), cluster.username().get(), cluster.password().get()),
                cluster.amqp(), null));
    }

    /**
     * Whether a cluster has the one address a run cannot make up for it.
     *
     * <p>Checked for {@code apply} and not for {@code plan}, because the two need different things
     * and the validator is right to call this a warning: a plan reads both clusters over the
     * management API and never dials AMQP at all. A drain and a mirror are declared <em>inside</em>
     * one broker and reach across to the other from there, so the URI a run needs is one this
     * process has no way to derive — the address on the operator's laptop is the wrong one by
     * construction.
     */
    private boolean dialable(Side side) {
        if (side.amqpUri().isPresent()) {
            return true;
        }
        err.println("acemq-infra: cluster '" + side.access().name() + "' has no amqp: URI, and a"
                + " cutover cannot be run without one. A drain is declared inside one broker and"
                + " dials the other, so this is an address the file has to give: the management URL"
                + " and the operator's own address are both the wrong one.");
        return false;
    }

    private Optional<DeploymentFile> read(String path, Environment lookup) {
        try {
            return Optional.of(DeploymentFiles.load(Path.of(path), lookup));
        } catch (ConfigException unreadable) {
            // Every problem at once. A file with four unset variables should say so in one run
            // rather than over four.
            err.println(path + ": cannot be read as a deployment file.");
            unreadable.problems().forEach(problem -> err.println("  " + problem));
            return Optional.empty();
        } catch (UncheckedIOException missing) {
            err.println("acemq-infra: " + missing.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One of the two clusters the file names.
     *
     * @param access where it is and who to be
     * @param amqpUri its AMQP URI <em>as the other broker will dial it</em>, which the file may not
     *     have given: a plan does not need it and the validator warns rather than refuses
     * @param probed what the probe found, or null before it has been asked
     */
    private record Side(ClusterAccess access, Optional<String> amqpUri, ProbedCluster probed) {

        Side probedBy(Prober prober) {
            return new Side(access, amqpUri, prober.probe(access));
        }

        Run.Side side(Broker broker) {
            return new Run.Side(access.name(), probed, broker, amqpUri.orElseThrow());
        }
    }

    /** A validated file and two clusters that answered. */
    private record Probed(DeploymentFile file, Side source, Side target) {
    }

    /**
     * Either both clusters, or the exit code of whatever stopped the command reaching them.
     *
     * @param probed the file and both sides, when there are two
     * @param code what to exit with when there are not
     */
    private record Ready(Optional<Probed> probed, int code) {

        static Ready of(Probed probed) {
            return new Ready(Optional.of(probed), OK);
        }

        static Ready no(int code) {
            return new Ready(Optional.empty(), code);
        }
    }

    // ---------------------------------------------------------------- the command line

    /**
     * What was on the command line.
     *
     * @param file the deployment file to read
     * @param requireVariables whether an unset {@code ${VAR}} is an error rather than a warning
     * @param dryRun whether apply rehearses instead of writing
     * @param yes whether the confirmation in front of the first write has already been given
     */
    private record Options(String file, boolean requireVariables, boolean dryRun, boolean yes) {

        static Options of(List<String> arguments) {
            String file = null;
            boolean require = false;
            boolean dry = false;
            boolean yes = false;
            for (int index = 0; index < arguments.size(); index++) {
                String argument = arguments.get(index);
                switch (argument) {
                    case "-f":
                    case "--file":
                        if (index + 1 >= arguments.size()) {
                            throw new IllegalArgumentException(argument + " needs a file.");
                        }
                        file = arguments.get(++index);
                        break;
                    case "--require-variables":
                        require = true;
                        break;
                    case "--dry-run":
                        dry = true;
                        break;
                    case "--yes":
                        yes = true;
                        break;
                    default:
                        throw new IllegalArgumentException("'" + argument + "' is not an option"
                                + " this command takes.");
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("which file? Pass -f deployment.yaml.");
            }
            return new Options(file, require, dry, yes);
        }
    }

    private void usage(PrintStream stream) {
        stream.println("""
                acemq-infra — read a deployment file, say what a cutover would do, and do it.

                  acemq-infra validate -f FILE [--require-variables]
                  acemq-infra plan     -f FILE
                  acemq-infra apply    -f FILE [--dry-run] [--yes]

                validate  reads the file and checks it against the documented format and the
                          handful of rules that cost messages when they are broken. It never
                          connects to anything. A ${VAR} that is not set here is reported and
                          the file is still checked; --require-variables makes it an error,
                          which is what plan and apply do.

                plan      probes both clusters, expands the step list, and prints what a
                          cutover would do, step by step, with the capabilities each step
                          needs and the guards it will wait on. It writes nothing to either
                          broker. Unset variables are fatal: it is about to authenticate.

                apply     carries the steps out. It prints the plan, stops, and asks — and
                          the only thing that gets past that is the word yes, typed at a
                          terminal. --yes says a pipeline has already decided; it consents to
                          the run STARTING and to nothing afterwards, so a prompt or an
                          external endpoint switch in a run with nobody watching still stops
                          it. --dry-run re-probes both clusters and rehearses every step
                          against them, reporting what each would do at this moment. A
                          rehearsal cannot write: the brokers it is given throw on every
                          verb that would.

                Exit codes: 0 ok, 1 findings, a refused plan, or a run that was refused,
                stopped or aborted, 2 a bad command line.""");
    }

    /** The version, from the jar's manifest, or a word saying there is no jar. */
    private static String version() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "(from classes, no version)" : version;
    }
}
