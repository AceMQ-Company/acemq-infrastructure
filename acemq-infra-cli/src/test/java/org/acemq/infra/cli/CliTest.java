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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acemq.infra.config.Environment;
import org.acemq.infra.execute.Console;
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
 * The three commands, run end to end against clusters that do not exist.
 *
 * <p>The prober and the broker factory are constructor arguments for exactly this: they are the
 * only two things in the commands that reach a broker, so replacing them makes {@code plan} a
 * function from a file to a string and {@code apply} a function from a file to a string and a list
 * of verbs. A string is a thing a test can hold to the shape docs/roadmap.md specifies, and a list
 * of verbs is how "nothing was written" is asserted rather than asserted about.
 *
 * <p>The console is the third, and it is the one that carries the most weight. Whether there is
 * anybody watching is a fact about the process — {@link Terminal} decides it, from the process —
 * and handing it in here is what lets the suite ask what a pipeline gets without being one.
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

    /**
     * The same file with a step list that waits for nothing.
     *
     * <p>Here because the default list is nine steps and four of them are guards, and a guard is
     * the one thing in a cutover that takes real time: the settle window is fifteen seconds of wall
     * clock and a suite that waited out four of them would be a suite nobody runs before pushing.
     * What the guards do is {@code acemq-infra-execute}'s subject and is tested there against a
     * clock that only moves when something waits. What is being tested here is the command — the
     * gate in front of the first write, the exit code that comes out, and which verbs were reached.
     */
    private static final String NO_GUARDS = FILE.replace("    enabled: true", "    enabled: false")
            + """
                  steps:
                    - id: probe
                      requires:
                        - TOPOLOGY_EXPORT
                        - TOPOLOGY_IMPORT_MERGE
                    - id: topology
                      copyTopology:
                        from: blue
                        to: green
                        exclude: [policies, operatorPolicies]
                    - id: policies
                      copyTopology:
                        from: blue
                        to: green
                        include: [policies, operatorPolicies]
                """;

    @TempDir
    private Path directory;

    private ByteArrayOutputStream output;
    private ByteArrayOutputStream errors;
    private List<ClusterAccess> probed;
    private Map<String, Recorder> opened;
    private Cli cli;

    @BeforeEach
    void setUp() {
        output = new ByteArrayOutputStream();
        errors = new ByteArrayOutputStream();
        probed = new ArrayList<>();
        opened = new LinkedHashMap<>();
        cli = cli(prober(), nobodyWatching());
    }

    private Cli cli(Prober prober, Console console) {
        return new Cli(stream(output), stream(errors), Environment.of(VARIABLES), prober,
                access -> opened.computeIfAbsent(access.name(), Recorder::new), console);
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

    /** What a pipeline has: somewhere for the lines to go, and nobody to answer a question. */
    private Console nobodyWatching() {
        return Console.unattended(line -> { });
    }

    /** What a terminal is, for the purposes of this suite: somebody who always says the same word. */
    private Console saying(Console.Answer answer) {
        return new Console() {
            @Override
            public void say(String line) {
            }

            @Override
            public Answer ask(String question) {
                asked.add(question);
                return answer;
            }
        };
    }

    /** Every question a run put to the console, in order. */
    private final List<String> asked = new ArrayList<>();

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

    /** Every writing verb reached on either cluster, which is usually asserted to be none. */
    private List<String> written() {
        return opened.values().stream().flatMap(broker -> broker.wrote.stream()).toList();
    }

    @Nested
    @DisplayName("the command line")
    class CommandLine {

        @Test
        @DisplayName("with nothing on it explains itself and fails")
        void nothing() {
            assertThat(cli.run()).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("acemq-infra validate", "acemq-infra plan",
                    "acemq-infra apply");
        }

        @Test
        @DisplayName("asked for help explains itself and does not fail")
        void help() {
            assertThat(cli.run("--help")).isEqualTo(Cli.OK);
            assertThat(out()).contains("It writes nothing to either");
        }

        @Test
        @DisplayName("says what apply does before it writes, in the help somebody reads first")
        void helpSaysWhatApplyDoes() {
            cli.run("--help");
            assertThat(out())
                    .contains("prints the plan, stops, and asks")
                    .contains("the run STARTING and to nothing afterwards")
                    .contains("rehearsal cannot write");
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
            assertThat(cli.run("plan", "-f", "x.yaml", "--force")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("'--force' is not an option");
        }

        @Test
        @DisplayName("says which file it could not find")
        void missingFile() {
            assertThat(cli.run("validate", "-f", "nowhere.yaml")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("nowhere.yaml");
        }

        @Test
        @DisplayName("refuses --dry-run on a command that never had a wet one")
        void dryRunBelongsToApply() throws IOException {
            String file = write(FILE).toString();
            assertThat(cli.run("plan", "-f", file, "--dry-run")).isEqualTo(Cli.USAGE);
            assertThat(cli.run("validate", "-f", file, "--dry-run")).isEqualTo(Cli.USAGE);
            assertThat(err()).contains("--dry-run is an apply option");
        }

        @Test
        @DisplayName("refuses --yes on a command with nothing to agree to")
        void yesBelongsToApply() throws IOException {
            assertThat(cli.run("plan", "-f", write(FILE).toString(), "--yes"))
                    .isEqualTo(Cli.USAGE);
            assertThat(err()).contains("--yes is an apply option");
        }

        @Test
        @DisplayName("refuses --yes and --dry-run together rather than picking one")
        void yesAndDryRunSayOppositeThings() throws IOException {
            assertThat(cli.run("apply", "-f", write(FILE).toString(), "--yes", "--dry-run"))
                    .isEqualTo(Cli.USAGE);
            assertThat(err()).contains("A dry run writes nothing, so there is nothing to agree to");
            assertThat(probed).isEmpty();
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
            return new Cli(stream(output), stream(errors), Environment.of(Map.of()), prober(),
                    access -> opened.computeIfAbsent(access.name(), Recorder::new),
                    nobodyWatching());
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
        @DisplayName("stops apply before it opens a single connection")
        void applyRefuses() throws IOException {
            assertThat(bare().run("apply", "-f", write(FILE).toString(), "--yes"))
                    .isEqualTo(Cli.USAGE);
            assertThat(probed).isEmpty();
            assertThat(opened).isEmpty();
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
        @DisplayName("has no equivalent flag on plan or apply, which have no second mode")
        void notOnTheOthers() throws IOException {
            String file = write(FILE).toString();
            assertThat(cli.run("plan", "-f", file, "--require-variables")).isEqualTo(Cli.USAGE);
            assertThat(cli.run("apply", "-f", file, "--require-variables")).isEqualTo(Cli.USAGE);
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
        @DisplayName("opens no connection that could change anything")
        void opensNothingThatWrites() throws IOException {
            cli.run("plan", "-f", write(FILE).toString());
            assertThat(opened).isEmpty();
        }

        @Test
        @DisplayName("prints the plan, ending with the command that would carry it out")
        void printsThePlan() throws IOException {
            cli.run("plan", "-f", write(FILE).toString());
            assertThat(out())
                    .startsWith("orders-blue-green — blueGreen, blue → green,"
                            + " semantics=atLeastOnce")
                    .contains("  probe             blue  RabbitMQ 4.0.5")
                    .contains("1 backup")
                    .contains("9 verify")
                    .endsWith("nothing was written. run `acemq-infra apply -f` on this file to"
                            + " execute it, or `apply --dry-run` to see what each step would do"
                            + " right now.\n");
        }

        @Test
        @DisplayName("refuses a file with errors before it opens a connection")
        void validatesFirst() throws IOException {
            String broken = FILE.replace("  from: blue\n", "  from: chartreuse\n");
            assertThat(cli.run("plan", "-f", write(broken).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(probed).isEmpty();
            assertThat(err()).contains("Not run.");
        }

        @Test
        @DisplayName("fails when the plan is refused, and still says nothing was written")
        void refusedPlanFails() throws IOException {
            assertThat(cli(refusing(), nobodyWatching())
                    .run("plan", "-f", write(FILE).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(out())
                    .contains("DRAIN_BY_SHOVEL")
                    .contains("rabbitmq_shovel is not enabled")
                    .endsWith("nothing was written, and nothing would be: this plan is refused.\n");
        }

        @Test
        @DisplayName("says which cluster it could not reach rather than printing a stack trace")
        void unreachable() throws IOException {
            Cli broken = cli(access -> {
                throw new IllegalStateException("could not probe cluster '" + access.name()
                        + "': connection refused");
            }, nobodyWatching());
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

        @Test
        @DisplayName("does not need an amqp URI, because it never dials one")
        void noAmqpIsFineHere() throws IOException {
            String without = FILE.replace("    amqp: ${GREEN_AMQP_URL}\n", "");
            assertThat(cli.run("plan", "-f", write(without).toString())).isEqualTo(Cli.OK);
            assertThat(err()).contains("no amqp URI");
        }
    }

    /** A cluster on an old broker with the shovel plugin off, which refuses the plan. */
    private Prober refusing() {
        return access -> {
            probed.add(access);
            return ProbedCluster.named(access.name()).version("3.6.16")
                    .facility("shovel", false)
                    .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled")
                    .can(Capability.TOPOLOGY_EXPORT, "read")
                    .can(Capability.TOPOLOGY_IMPORT_MERGE, "administrator")
                    .can(Capability.CONNECTION_CLOSE, "administrator")
                    .can(Capability.CONSUMER_INSPECT, "read")
                    .build();
        };
    }

    @Nested
    @DisplayName("apply, before it writes anything")
    class TheGate {

        @Test
        @DisplayName("prints the plan first, because a gate in front of nothing is not a gate")
        void printsThePlanBeforeAsking() throws IOException {
            cli(prober(), saying(Console.Answer.STOP)).run("apply", "-f", write(FILE).toString());
            assertThat(out()).contains("orders-blue-green — blueGreen", "6 drain-messages");
        }

        @Test
        @DisplayName("stops when the answer is anything but yes, and has written nothing")
        void aRefusalStops() throws IOException {
            assertThat(cli(prober(), saying(Console.Answer.STOP))
                    .run("apply", "-f", write(FILE).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(err()).contains("not confirmed. Nothing was written");
            assertThat(opened).isEmpty();
        }

        @Test
        @DisplayName("says what leaving the source costs, in the question itself")
        void theQuestionSaysWhatItCosts() throws IOException {
            cli(prober(), saying(Console.Answer.STOP)).run("apply", "-f", write(FILE).toString());
            assertThat(asked).singleElement().asString()
                    .contains("Messages leave blue")
                    .contains("a second cutover in the other direction, not a switch back");
        }

        @Test
        @DisplayName("refuses outright in a pipeline rather than prompting into a void")
        void nobodyThereIsNotAYes() throws IOException {
            assertThat(cli.run("apply", "-f", write(FILE).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(err())
                    .contains("there is no terminal here to ask")
                    .contains("pass --yes")
                    .contains("Nothing was written");
            assertThat(opened).isEmpty();
        }

        @Test
        @DisplayName("refuses a plan that is refused, without opening a connection that writes")
        void aRefusedPlanIsNotRun() throws IOException {
            assertThat(cli(refusing(), saying(Console.Answer.PROCEED))
                    .run("apply", "-f", write(FILE).toString())).isEqualTo(Cli.FINDINGS);
            assertThat(err()).contains("the plan is refused, so nothing was run");
            assertThat(asked).isEmpty();
            assertThat(opened).isEmpty();
        }

        @Test
        @DisplayName("refuses a cluster with no amqp URI, which a drain cannot be declared without")
        void noAmqpIsFatalHere() throws IOException {
            String without = FILE.replace("    amqp: ${GREEN_AMQP_URL}\n", "");
            assertThat(cli(prober(), saying(Console.Answer.PROCEED))
                    .run("apply", "-f", write(without).toString(), "--yes"))
                    .isEqualTo(Cli.USAGE);
            assertThat(err()).contains("cluster 'green' has no amqp: URI");
            assertThat(opened).isEmpty();
        }
    }

    @Nested
    @DisplayName("apply, once it is past the gate")
    class Applying {

        @Test
        @DisplayName("--yes gets a pipeline started and still cannot answer what the run asks")
        void yesConsentsToStartingAndNothingElse() throws IOException {
            // The whole of the agreement between this command and the executor, in one run. --yes
            // is enough to begin; the endpoint switch in the default list is external, an external
            // switch waits for a person by definition, and there is nobody here. The executor
            // refuses that before the first write rather than discovering it at step eight with
            // the drain already done.
            assertThat(cli.run("apply", "-f", write(FILE).toString(), "--yes"))
                    .isEqualTo(Cli.FINDINGS);
            assertThat(out())
                    .contains("--yes was given")
                    .contains("orders-blue-green — cutover — refused")
                    .contains("there is nobody watching this run")
                    .endsWith("nothing was written: the run was refused before the first step.\n");
            assertThat(written()).isEmpty();
        }

        @Test
        @DisplayName("carries the steps out and exits 0 when they all happen")
        void aCutoverThatCompletes() throws IOException {
            assertThat(cli(prober(), saying(Console.Answer.PROCEED))
                    .run("apply", "-f", write(NO_GUARDS).toString())).isEqualTo(Cli.OK);
            assertThat(out())
                    .contains("orders-blue-green — cutover — completed")
                    .endsWith("the cutover completed. nothing that happened needs undoing.\n");
            assertThat(written()).containsExactly("applyTopology", "applyTopology");
        }

        @Test
        @DisplayName("needs no terminal for a run that never stops to ask")
        void unattendedIsFineWhenNothingAsks() throws IOException {
            // The other half of the agreement, and the reason the refusal above is not simply
            // "apply needs a terminal". A run whose steps include no external switch and no
            // prompt has nothing to ask anybody, and a pipeline is exactly where it belongs.
            assertThat(cli.run("apply", "-f", write(NO_GUARDS).toString(), "--yes"))
                    .isEqualTo(Cli.OK);
            assertThat(asked).isEmpty();
            assertThat(written()).containsExactly("applyTopology", "applyTopology");
        }
    }

    @Nested
    @DisplayName("apply --dry-run")
    class DryRun {

        @Test
        @DisplayName("asks the live clusters rather than replaying what the plan said")
        void itProbes() throws IOException {
            assertThat(cli.run("apply", "-f", write(FILE).toString(), "--dry-run"))
                    .isEqualTo(Cli.OK);

            // Both clusters probed again, and then read again, step by step: this is what makes a
            // rehearsal different from the plan printed twice. The plan is a function of two
            // snapshots; these are the verbs a step would use, reaching the cluster now.
            assertThat(probed).extracting(ClusterAccess::name).containsExactly("blue", "green");
            assertThat(opened.get("blue").read)
                    .contains("snapshotTopology", "listAttachments", "measure");
        }

        @Test
        @DisplayName("reports what a guard's condition is at this moment, not what it will wait for")
        void guardsSayWhatIsTrueNow() throws IOException {
            cli.run("apply", "-f", write(FILE).toString(), "--dry-run");
            assertThat(out())
                    .contains("right now that is satisfied")
                    .contains("a cutover would wait");
        }

        @Test
        @DisplayName("writes nothing, and is arranged so a step that forgot would fail loudly")
        void writesNothing() throws IOException {
            cli.run("apply", "-f", write(FILE).toString(), "--dry-run");
            assertThat(written()).isEmpty();
            assertThat(out()).endsWith("nothing was written: this was a rehearsal.\n");
        }

        @Test
        @DisplayName("takes the backup as a step it would do rather than a file it writes")
        void theBackupIsRehearsedToo() throws IOException {
            cli.run("apply", "-f", write(FILE).toString(), "--dry-run");
            assertThat(out()).contains("1 backup").contains("would");
            assertThat(Path.of("./backups")).doesNotExist();
        }

        @Test
        @DisplayName("asks nobody anything, so it needs no terminal and no --yes")
        void noGateInFrontOfARehearsal() throws IOException {
            assertThat(cli(prober(), saying(Console.Answer.STOP))
                    .run("apply", "-f", write(FILE).toString(), "--dry-run")).isEqualTo(Cli.OK);
            assertThat(asked).isEmpty();
        }

        @Test
        @DisplayName("refuses a refused plan rather than rehearsing something that cannot run")
        void aRefusedPlanIsNotRehearsedEither() throws IOException {
            assertThat(cli(refusing(), nobodyWatching())
                    .run("apply", "-f", write(FILE).toString(), "--dry-run"))
                    .isEqualTo(Cli.FINDINGS);
            assertThat(err()).contains("the plan is refused, so nothing was run");
        }
    }
}
