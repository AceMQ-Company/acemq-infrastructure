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
package org.acemq.infra.operator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.acemq.infra.cli.Cli;
import org.acemq.infra.config.Cluster;
import org.acemq.infra.config.ConfigException;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFiles;
import org.acemq.infra.config.Environment;
import org.acemq.infra.execute.Console;
import org.acemq.infra.execute.Journal;
import org.acemq.infra.execute.rabbitmq.RabbitBroker;
import org.acemq.infra.plan.Plan;
import org.acemq.infra.plan.PlannedStep;
import org.acemq.infra.plan.Planner;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.rabbitmq.RabbitProbe;
import org.acemq.infra.validate.Finding;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;

/**
 * Everything the operator asks of the clusters, behind one seam so the state machine can be tested
 * without any.
 *
 * <p>{@link #apply} and {@link #rollback} are {@code acemq-infra apply --yes} and
 * {@code acemq-infra rollback --yes}, run through {@link Cli} itself. {@code --yes} is honest
 * here: the approval was given, by name, in the resource, before this is called.
 */
interface Engine {

    /**
     * Probes both clusters and plans, as {@code acemq-infra plan} does.
     *
     * @param file the deployment file
     * @param env the variables it is read with
     * @param sources how each variable was given, for the fingerprint, with no secret value in it
     * @return the plan
     */
    Planned plan(Path file, Map<String, String> env, String sources);

    /** {@code acemq-infra apply -f FILE --yes --journal JOURNAL}. */
    Ran apply(Path file, Path journal, Map<String, String> env, Journal.Mirror mirror);

    /** {@code acemq-infra rollback --journal JOURNAL -f FILE --yes}. */
    Ran rollback(Path file, Path journal, Map<String, String> env, Journal.Mirror mirror);

    /**
     * @param ok whether it can run
     * @param text what {@code acemq-infra plan} would print, or why there is no plan
     * @param fingerprint the plan's name, for {@code spec.approve}; null when there is no plan
     */
    record Planned(boolean ok, String text, String fingerprint) {
    }

    /**
     * @param code the CLI's exit code
     * @param output everything it printed
     */
    record Ran(int code, String output) {
    }

    /** Against real RabbitMQ clusters. */
    final class Rabbit implements Engine {

        @Override
        public Planned plan(Path file, Map<String, String> env, String sources) {
            DeploymentFile read;
            try {
                read = DeploymentFiles.load(file, Environment.of(env));
            } catch (ConfigException unreadable) {
                return new Planned(false, "cannot be read as a deployment file:\n  "
                        + String.join("\n  ", unreadable.problems().stream()
                                .map(Object::toString).toList()) + "\n", null);
            }
            ValidationReport report = Validator.validate(read);
            List<String> lines = new ArrayList<>();
            for (Finding finding : report.errors()) {
                lines.add("error: " + finding);
            }
            if (!report.ok()) {
                return new Planned(false, String.join("\n", lines) + "\n", null);
            }
            report.warnings().forEach(finding -> lines.add("warning: " + finding));
            Deployment deployment = read.deployment().orElseThrow();
            ClusterAccess source = access(read, deployment.from().orElse(""));
            ClusterAccess target = access(read, deployment.to().orElse(""));
            RabbitProbe prober = new RabbitProbe();
            Plan plan = Planner.plan(read, prober.probe(source), prober.probe(target));
            String text = (lines.isEmpty() ? "" : String.join("\n", lines) + "\n\n")
                    + plan.render();
            return new Planned(plan.ok(), text,
                    fingerprint(file, sources, plan, source, target));
        }

        @Override
        public Ran apply(Path file, Path journal, Map<String, String> env, Journal.Mirror mirror) {
            return cli(env, mirror, "apply", "-f", file.toString(), "--yes", "--journal",
                    journal.toString());
        }

        @Override
        public Ran rollback(Path file, Path journal, Map<String, String> env,
                            Journal.Mirror mirror) {
            return cli(env, mirror, "rollback", "--journal", journal.toString(), "-f",
                    file.toString(), "--yes");
        }

        private static Ran cli(Map<String, String> env, Journal.Mirror mirror, String... args) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            // Unattended: a prompt or an external endpoint switch in the middle of the run stops it
            // rather than being answered on somebody's behalf, exactly as in a pipeline.
            int code = new Cli(out, out, Environment.of(env), new RabbitProbe(),
                    RabbitBroker::open, Console.unattended(out::println))
                    .mirroringJournalsTo(mirror)
                    .run(args);
            out.flush();
            return new Ran(code, bytes.toString(StandardCharsets.UTF_8));
        }

        /** The same translation {@code Cli} makes, with the same refusal. */
        private static ClusterAccess access(DeploymentFile file, String name) {
            Cluster cluster = file.clusters().get(name);
            if (cluster == null || cluster.management().isEmpty() || cluster.username().isEmpty()
                    || cluster.password().isEmpty()) {
                throw new IllegalArgumentException("cluster '" + name + "' is not in the file, or"
                        + " has no management URL or no credentials, so it cannot be probed.");
            }
            return ClusterAccess.to(cluster.name(), cluster.management().get(),
                    cluster.vhost().orElse("/"), cluster.username().get(),
                    cluster.password().get());
        }

        /**
         * What an approval names: the file, how its variables were given, where the clusters are,
         * what they turned out to be, and the numbered steps. Not the plan's text, which counts
         * live messages and would never match twice.
         */
        static String fingerprint(Path file, String sources, Plan plan, ClusterAccess source,
                                  ClusterAccess target) {
            StringBuilder named = new StringBuilder();
            try {
                named.append(java.nio.file.Files.readString(file)).append('\0');
            } catch (java.io.IOException unreadable) {
                throw new java.io.UncheckedIOException(unreadable);
            }
            named.append(sources).append('\0').append(plan.headline()).append('\0');
            for (ClusterAccess access : List.of(source, target)) {
                named.append(access.name()).append(' ').append(access.redactedManagement())
                        .append('\0');
            }
            for (ProbedCluster probed : List.of(plan.source(), plan.target())) {
                named.append(probed.name()).append(' ').append(probed.release()).append(' ')
                        .append(probed.facilityMarks()).append('\0');
            }
            for (PlannedStep step : plan.steps()) {
                named.append(step.number()).append(' ').append(step.id()).append('\0');
            }
            plan.refusals().forEach(refusal -> named.append(refusal).append('\0'));
            return sha256(named.toString()).substring(0, 16);
        }

        static String sha256(String text) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
