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
import org.acemq.infra.plan.Plan;
import org.acemq.infra.plan.Planner;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.Prober;
import org.acemq.infra.validate.Finding;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;

/**
 * {@code acemq-infra validate} and {@code acemq-infra plan}, and nothing else.
 *
 * <p>Phase 1 stops there, and the stopping is the deliverable — docs/roadmap.md's one sentence for
 * this milestone ends "and writes nothing to either broker". So there is no {@code apply}, and
 * there is no flag on {@code plan} that sounds like one. An unimplemented subcommand that prints
 * "not yet" is worse than an absent one: it is a thing somebody puts in a pipeline.
 *
 * <p>Everything that varies is a constructor argument — the two streams, the environment,
 * and the one verb that reaches a broker — so the command can be run end to end in a test against
 * a constructed cluster and the output compared, rather than eyeballed once by whoever wrote it.
 */
public final class Cli {

    /** The file was read, the rules hold, and any plan produced can run. */
    public static final int OK = 0;

    /** The file has errors, or the plan is refused. Nothing was written either way. */
    public static final int FINDINGS = 1;

    /** The command line itself was wrong, or the file could not be read at all. */
    public static final int USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;
    private final Environment environment;
    private final Prober prober;

    /**
     * @param out where output goes
     * @param err where complaints about the command line go
     * @param environment where {@code ${VAR}} is looked up
     * @param prober the one thing here that touches a broker
     */
    public Cli(PrintStream out, PrintStream err, Environment environment, Prober prober) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.prober = prober;
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
                return validate(options);
            case "plan":
                if (options.requireVariables()) {
                    // The flag exists to make validate behave as plan already does. Accepting it
                    // on plan would suggest plan has another mode, and it does not.
                    err.println("acemq-infra: --require-variables is a validate option. plan"
                            + " always requires them: it is about to authenticate.");
                    return USAGE;
                }
                return plan(options);
            default:
                err.println("acemq-infra: there is no '" + command + "' command.");
                usage(err);
                return USAGE;
        }
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
        Optional<DeploymentFile> read = read(options.file(), environment);
        if (read.isEmpty()) {
            return USAGE;
        }
        DeploymentFile file = read.get();

        // Validated before a single connection is opened. A file with a drain in the wrong place
        // should be refused by the thing that can see it is in the wrong place, not by a broker
        // that will do exactly what it is told.
        ValidationReport report = Validator.validate(file);
        if (!report.ok()) {
            err.println(options.file() + ": " + summary(report) + ". Not planned.");
            report.errors().forEach(finding -> err.println("  error: " + finding));
            return FINDINGS;
        }
        report.warnings().forEach(finding -> err.println("  warning: " + finding));

        Deployment deployment = file.deployment().orElseThrow();
        Optional<ClusterAccess> source = access(file, deployment.from());
        Optional<ClusterAccess> target = access(file, deployment.to());
        if (source.isEmpty() || target.isEmpty()) {
            return USAGE;
        }

        ProbedCluster probedSource;
        ProbedCluster probedTarget;
        try {
            probedSource = prober.probe(source.get());
            probedTarget = prober.probe(target.get());
        } catch (RuntimeException unreachable) {
            err.println("acemq-infra: " + unreachable.getMessage());
            return FINDINGS;
        }

        Plan plan = Planner.plan(file, probedSource, probedTarget);
        out.print(plan.render());
        return plan.ok() ? OK : FINDINGS;
    }

    /**
     * Turns a named cluster in the file into the five things a provider needs.
     *
     * <p>The translation is here rather than in the provider because the provider package is not
     * allowed to know a deployment file exists — docs/library.md — and this is the seam where that
     * is paid for. It is five lines and it buys a probe that a Kubernetes secret or a test can
     * drive without a YAML document in the picture.
     */
    private Optional<ClusterAccess> access(DeploymentFile file, Optional<String> name) {
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
        return Optional.of(ClusterAccess.to(cluster.name(), cluster.management().get(),
                cluster.vhost().orElse("/"), cluster.username().get(), cluster.password().get()));
    }

    // ---------------------------------------------------------------- reading

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

    // ---------------------------------------------------------------- the command line

    /**
     * What was on the command line.
     *
     * @param file the deployment file to read
     * @param requireVariables whether an unset {@code ${VAR}} is an error rather than a warning
     */
    private record Options(String file, boolean requireVariables) {

        static Options of(List<String> arguments) {
            String file = null;
            boolean require = false;
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
                    default:
                        throw new IllegalArgumentException("'" + argument + "' is not an option"
                                + " this command takes.");
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("which file? Pass -f deployment.yaml.");
            }
            return new Options(file, require);
        }
    }

    private void usage(PrintStream stream) {
        stream.println("""
                acemq-infra — read a deployment file, and say what a cutover would do.

                  acemq-infra validate -f FILE [--require-variables]
                  acemq-infra plan     -f FILE

                validate  reads the file and checks it against the documented format and the
                          handful of rules that cost messages when they are broken. It never
                          connects to anything. A ${VAR} that is not set here is reported and
                          the file is still checked; --require-variables makes it an error,
                          which is what plan does.

                plan      probes both clusters, expands the step list, and prints what a
                          cutover would do, step by step, with the capabilities each step
                          needs and the guards it will wait on. It writes nothing to either
                          broker. Unset variables are fatal: it is about to authenticate.

                There is no apply. Executing a plan is phase 2 — docs/roadmap.md.

                Exit codes: 0 ok, 1 findings or a refused plan, 2 a bad command line.""");
    }

    /** The version, from the jar's manifest, or a word saying there is no jar. */
    private static String version() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "(from classes, no version)" : version;
    }
}
