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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.acemq.infra.config.Environment;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.Prober;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two commands, run end to end against a cluster that does not exist.
 *
 * <p>The prober is a constructor argument for exactly this: it is the only thing in the command
 * that reaches a broker, so replacing it makes {@code plan} a function from a file to a string,
 * and a string is a thing a test can hold to the shape docs/roadmap.md specifies. The alternative
 * is a command whose output is checked once, by hand, by whoever wrote it.
 */
class CliTest {

    /** Everything the worked example refers to, with values of the right shape. */
    private static final Map<String, String> VARIABLES = Map.ofEntries(
            Map.entry("BLUE_MGMT_URL", "https://blue.internal:15671"),
            Map.entry("BLUE_AMQP_URL", "amqps://blue.internal:5671"),
            Map.entry("BLUE_USERNAME", "cutover"),
            Map.entry("BLUE_PASSWORD", "s3cret-blue"),
            Map.entry("GREEN_MGMT_URL", "https://green.internal:15671"),
            Map.entry("GREEN_AMQP_URL", "amqps://green.internal:5671"),
            Map.entry("GREEN_USERNAME", "cutover"),
            Map.entry("GREEN_PASSWORD", "s3cret-green"),
            Map.entry("CA_FILE", "/etc/ssl/certs/internal-ca.pem"),
            Map.entry("ANNOUNCE_EXCHANGE", "orders.events"));

    private static final String FILE = """
            apiVersion: acemq.org/v1alpha1
            kind: Deployment
            metadata:
              name: orders-blue-green
            provider: rabbitmq
            clusters:
              blue:
                management: ${BLUE_MGMT_URL}
                amqp: ${BLUE_AMQP_URL}
                vhost: /orders
                username: ${BLUE_USERNAME}
                password: ${BLUE_PASSWORD}
              green:
                management: ${GREEN_MGMT_URL}
                amqp: ${GREEN_AMQP_URL}
                vhost: /orders
                username: ${GREEN_USERNAME}
                password: ${GREEN_PASSWORD}
            endpoint:
              kind: external
              description: orders-amqp.internal is a CNAME switched by the platform team
            streams:
              acknowledged: true
            deployment:
              operation: blueGreen
              from: blue
              to: green
              semantics: atLeastOnce
              backup:
                enabled: true
                path: ./backups/{{name}}-{{timestamp}}.json
              announce:
                exchange: ${ANNOUNCE_EXCHANGE}
                routingKey: deployment.started
            """;

    @TempDir
    private Path directory;

    private ByteArrayOutputStream output;
    private ByteArrayOutputStream errors;
    private List<ClusterAccess> probed;
    private Cli cli;

    @BeforeEach
    void setUp() {
        output = new ByteArrayOutputStream();
        errors = new ByteArrayOutputStream();
        probed = new ArrayList<>();
        cli = new Cli(stream(output), stream(errors), Environment.of(VARIABLES), prober());
    }

    private static PrintStream stream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    /** A cluster that answers whatever it is asked, and remembers having been asked. */
    private Prober prober() {
        return access -> {
            probed.add(access);
            return ProbedCluster.named(access.name()).version("4.0.5")
                    .facility("shovel", true).facility("federation", true).facility("streams", true)
                    .inventory(Inventory.counting().exchanges(3).bindings(7).users(2)
                            .queue("orders.new", "classic", 120, 1).build())
                    .can(Capability.values())
                    .build();
        };
    }

    private Path write(String contents) throws IOException {
        Path file = directory.resolve("orders.yaml");
        Files.writeString(file, contents);
        return file;
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errors.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the command line")
    class CommandLine {

        @Test
        @DisplayName("with nothing on it explains itself and fails")
        void nothing() {
            assertThat(cli.run()).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("acemq-infra validate", "acemq-infra plan");
        }

        @Test
        @DisplayName("asked for help explains itself and does not fail")
        void help() {
            assertThat(cli.run("--help")).isEqualTo(Cli.OK);
            assertThat(out()).contains("It writes nothing to either");
        }

        @Test
        @DisplayName("says there is no apply, and where it went")
        void thereIsNoApply() {
            cli.run("--help");
            assertThat(out()).contains("There is no apply. Executing a plan is phase 2");
            assertThat(cli.run("apply", "-f", "whatever.yaml")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("there is no 'apply' command");
        }

        @Test
        @DisplayName("refuses a command with no file rather than guessing at one")
        void noFile() {
            assertThat(cli.run("validate")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("which file?");
        }

        @Test
        @DisplayName("refuses an option it does not have")
        void unknownOption() {
            assertThat(cli.run("plan", "-f", "x.yaml", "--dry-run")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("'--dry-run' is not an option");
        }

        @Test
        @DisplayName("says which file it could not find")
        void missingFile() {
            assertThat(cli.run("validate", "-f", "nowhere.yaml")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("nowhere.yaml");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("says what the file is, in the linter's words")
        void ok() throws IOException {
            assertThat(cli.run("validate", "-f", write(FILE).toString())).isEqualTo(Cli.OK);
            assertThat(out()).contains("ok — blueGreen, 0 steps, 2 clusters");
        }

        @Test
        @DisplayName("never probes anything")
        void neverConnects() throws IOException {
            cli.run("validate", "-f", write(FILE).toString());
            assertThat(probed).isEmpty();
        }

        @Test
        @DisplayName("reports an error with the line it is on, and fails")
        void errors() throws IOException {
            String broken = FILE.replace("  semantics: atLeastOnce\n", "");
            assertThat(cli.run("validate", "-f", write(broken).toString()))
                    .isEqualTo(Cli.FINDINGS);
            assertThat(out()).contains("error: ").contains("deployment.semantics");
        }
    }

    @Nested
    @DisplayName("an unset ${VAR}")
    class UnsetVariables {

        /**
         * A command with nothing in its environment.
         *
         * <p>Built per test rather than held in a field: the streams it writes to are replaced
         * before every test, and a nested class's field initializer runs before that happens, so
         * a held instance would be writing into the previous test's buffer.
         */
        private Cli bare() {
            return new Cli(stream(output), stream(errors), Environment.of(Map.of()), prober());
        }

        @Test
        @DisplayName("does not stop validate, because validate is never going to dial it")
        void validateCarriesOn() throws IOException {
            assertThat(bare().run("validate", "-f", write(FILE).toString())).isEqualTo(Cli.OK);
            assertThat(out()).contains("ok — blueGreen");
        }

        @Test
        @DisplayName("is reported by name, so a pipeline can see what it did not supply")
        void namesThem() throws IOException {
            bare().run("validate", "-f", write(FILE).toString());
            assertThat(out())
                    .contains("9 variables are not set here and were left as written")
                    .contains("BLUE_PASSWORD")
                    .contains("plan needs them set: it authenticates");
        }

        @Test
        @DisplayName("becomes an error when the pipeline asks for one")
        void requireVariables() throws IOException {
            assertThat(bare().run("validate", "-f", write(FILE).toString(), "--require-variables"))
                    .isEqualTo(Cli.USAGE);
            assertThat(err()).contains("is not set in this environment");
        }

        @Test
        @DisplayName("stops plan, which is about to authenticate")
        void planRefuses() throws IOException {
            assertThat(bare().run("plan", "-f", write(FILE).toString())).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("${BLUE_PASSWORD}").contains("is not set");
            assertThat(probed).isEmpty();
        }

        @Test
        @DisplayName("is reported all at once rather than one run at a time")
        void allOfThem() throws IOException {
            bare().run("plan", "-f", write(FILE).toString());
            // Nine variables, one run. A file with nine unset variables should say so once rather
            // than nine times over nine runs, each of which stops at the first.
            assertThat(err()).contains("BLUE_MGMT_URL", "BLUE_AMQP_URL", "BLUE_USERNAME",
                    "BLUE_PASSWORD", "GREEN_MGMT_URL", "GREEN_AMQP_URL", "GREEN_USERNAME",
                    "GREEN_PASSWORD", "ANNOUNCE_EXCHANGE");
        }

        @Test
        @DisplayName("has no equivalent flag on plan, because plan has no second mode")
        void notOnPlan() throws IOException {
            assertThat(cli.run("plan", "-f", write(FILE).toString(), "--require-variables"))
                    .isEqualTo(Cli.USAGE);
            assertThat(err()).contains("--require-variables is a validate option");
        }
    }

    @Nested
    @DisplayName("plan")
    class PlanCommand {

        @Test
        @DisplayName("probes both clusters, with the credentials the file gave for each")
        void probesBoth() throws IOException {
            assertThat(cli.run("plan", "-f", write(FILE).toString())).isEqualTo(Cli.OK);
            assertThat(probed).extracting(ClusterAccess::name).containsExactly("blue", "green");
            assertThat(probed).extracting(ClusterAccess::vhost).containsExactly("/orders",
                    "/orders");
            assertThat(probed.get(0).username()).isEqualTo("cutover");
            assertThat(probed.get(0).password()).isEqualTo("s3cret-blue");
        }

        @Test
        @DisplayName("prints the plan, ending with the line the milestone is about")
        void printsThePlan() throws IOException {
            cli.run("plan", "-f", write(FILE).toString());
            assertThat(out())
                    .startsWith("orders-blue-green — blueGreen, blue → green,"
                            + " semantics=atLeastOnce")
                    .contains("  probe             blue  RabbitMQ 4.0.5")
                    .contains("1 backup")
                    .contains("9 verify")
                    .endsWith("nothing was written. executing a plan is phase 2;"
                            + " this build plans only.\n");
        }

        @Test
        @DisplayName("refuses a file with errors before it opens a connection")
        void validatesFirst() throws IOException {
            String broken = FILE.replace("  from: blue\n", "  from: chartreuse\n");
            assertThat(cli.run("plan", "-f", write(broken).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(probed).isEmpty();
            assertThat(err()).contains("Not planned.");
        }

        @Test
        @DisplayName("fails when the plan is refused, and still says nothing was written")
        void refusedPlanFails() throws IOException {
            Cli refusing = new Cli(stream(output), stream(errors), Environment.of(VARIABLES),
                    access -> ProbedCluster.named(access.name()).version("3.6.16")
                            .facility("shovel", false)
                            .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled")
                            .can(Capability.TOPOLOGY_EXPORT, "read")
                            .can(Capability.TOPOLOGY_IMPORT_MERGE, "administrator")
                            .can(Capability.CONNECTION_CLOSE, "administrator")
                            .can(Capability.CONSUMER_INSPECT, "read")
                            .build());
            assertThat(refusing.run("plan", "-f", write(FILE).toString()))
                    .isEqualTo(Cli.FINDINGS);
            assertThat(out())
                    .contains("DRAIN_BY_SHOVEL")
                    .contains("rabbitmq_shovel is not enabled")
                    .endsWith("nothing was written, and nothing would be: this plan is refused.\n");
        }

        @Test
        @DisplayName("says which cluster it could not reach rather than printing a stack trace")
        void unreachable() throws IOException {
            Cli broken = new Cli(stream(output), stream(errors), Environment.of(VARIABLES),
                    access -> {
                        throw new IllegalStateException("could not probe cluster '" + access.name()
                                + "': connection refused");
                    });
            assertThat(broken.run("plan", "-f", write(FILE).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(err()).contains("could not probe cluster 'blue'");
            assertThat(out()).isEmpty();
        }

        @Test
        @DisplayName("prints the default step list in full, so it can be pasted into the file")
        void printsTheDefaultSteps() throws IOException {
            cli.run("plan", "-f", write(FILE).toString());
            assertThat(out())
                    .contains("2 topology")
                    .contains("6 drain-messages")
                    .contains("7 policies")
                    .contains("no steps: list");
        }
    }
}
