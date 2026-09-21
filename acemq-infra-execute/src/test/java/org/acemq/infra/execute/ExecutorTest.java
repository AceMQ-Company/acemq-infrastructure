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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.execute.Fakes.RecordingBroker;
import org.acemq.infra.execute.Fakes.Ticks;
import org.acemq.infra.execute.Fakes.Voice;
import org.acemq.infra.execute.Observation.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the executor does, against two brokers that do not exist.
 *
 * <p>The cases worth having are the ones where the tool could quietly do the wrong thing: a
 * rehearsal that writes, a stale reading that ends a drain, an abort that leaves a shovel running,
 * a prompt answered by nobody, an external endpoint in a pipeline. None of them needs a broker and
 * every one of them costs messages.
 */
class ExecutorTest {

    private RecordingBroker blue;
    private RecordingBroker green;
    private Ticks ticks;

    private Run.Builder runOf(DeploymentFile file) {
        blue = new RecordingBroker("blue");
        green = new RecordingBroker("green");
        ticks = new Ticks();
        return Run.of(file)
                .from(Deployments.side("blue", blue))
                .to(Deployments.side("green", green))
                .timing(ticks);
    }

    /** The default answers: everything settled, and a person at the terminal saying yes. */
    private Run.Builder settled(DeploymentFile file) {
        Run.Builder builder = runOf(file).console(new Voice(Console.Answer.PROCEED));
        blue.readings.add(RecordingBroker.idle());
        green.readings.add(RecordingBroker.idle());
        blue.attachments.add(new Broker.Attachment("10.0.0.1:52000", "orders-service", true));
        blue.attachments.add(new Broker.Attachment("10.0.0.2:52001", "orders-service", false));
        return builder;
    }

    @Nested
    @DisplayName("a cutover of the worked blue/green")
    class BlueGreen {

        @Test
        void runsTheNineStepsInTheDocumentedOrder() {
            Execution execution = Executor.execute(
                    settled(Deployments.blueGreen()).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
            // The backup is disabled in this file, so the numbering starts at the topology copy.
            // The order is the whole product -- docs/blue-green.md -- so it is asserted position
            // by position rather than as a set.
            assertThat(execution.steps().stream().map(Execution.Taken::id))
                    .containsExactly("topology", "announce-drain", "pause-producers",
                            "drain-consumers", "drain-messages", "policies", "switch-endpoint",
                            "verify");
            assertThat(execution.steps().stream().map(Execution.Taken::number))
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        }

        @Test
        void copiesTheShapeBeforeTheDrainAndTheRulesAfterIt() {
            Executor.execute(settled(Deployments.blueGreen()).cutover());

            // The single most valuable thing in the default step list, and the one arrangement no
            // combination of the old format's three booleans could express: an imported policy is
            // live the instant it lands, so a message-ttl arriving with the shape would discard
            // the backlog the drain is about to deliver.
            assertThat(green.wrote).containsSubsequence("applyTopology:6", "applyTopology:2");
            assertThat(green.wrote.indexOf("applyTopology:6"))
                    .isLessThan(green.wrote.indexOf("drain:orders.new,orders.notifications,orders.priority"));
            assertThat(green.wrote.indexOf("drain:orders.new,orders.notifications,orders.priority"))
                    .isLessThan(green.wrote.indexOf("applyTopology:2"));
        }

        @Test
        void drainsTheQueuesThePatternsSelectAndNotTheOneTheyExclude() {
            Executor.execute(settled(Deployments.blueGreen()).cutover());

            assertThat(green.wrote)
                    .contains("drain:orders.new,orders.notifications,orders.priority");
            assertThat(String.join(" ", green.wrote)).doesNotContain("orders.audit");
        }

        @Test
        void closesTheConsumingConnectionsAndLeavesThePublishingOnesAlone() {
            Executor.execute(settled(Deployments.blueGreen()).cutover());

            assertThat(blue.wrote).contains("detach:10.0.0.1:52000");
            assertThat(blue.wrote).doesNotContain("detach:10.0.0.2:52001");
        }

        @Test
        void derivesARollbackThatSwitchesBackFirstAndThenDrainsTheOtherWay() {
            Execution execution = Executor.execute(
                    settled(Deployments.blueGreen()).cutover());

            // The file writes its own rollback, so the derivation defers to it -- and the point of
            // asserting it here is that a completed cutover reports one at all.
            assertThat(execution.rollback().stream().map(step -> step.describeId()))
                    .containsExactly("switch-endpoint", "drain-back");
        }
    }

    @Nested
    @DisplayName("a rehearsal")
    class Rehearsing {

        @Test
        void writesNothingToEitherCluster() {
            Execution execution = Executor.execute(settled(Deployments.blueGreen()).rehearsal());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
            assertThat(blue.wrote).isEmpty();
            assertThat(green.wrote).isEmpty();
        }

        @Test
        void stillReadsBothClusters() {
            // Which is the whole difference between a rehearsal and the plan it came from: it
            // reports what each step would do at this moment rather than at the moment the plan
            // was written.
            Executor.execute(settled(Deployments.blueGreen()).rehearsal());

            assertThat(blue.read).contains("listAttachments");
            assertThat(blue.read).anyMatch(one -> one.startsWith("measure"));
        }

        @Test
        void saysWhetherEachGuardWouldPassRightNow() {
            Run.Builder builder = settled(Deployments.blueGreen());
            blue.whileDraining.add(RecordingBroker.backlog(27412));

            Execution execution = Executor.execute(builder.rehearsal());

            assertThat(line(execution, "drain-messages"))
                    .contains("depth=27412").contains("not_yet");
        }

        @Test
        void doesNotNeedAnybodyWatchingEvenWithAnExternalEndpoint() {
            // A pipeline has to be able to rehearse. It cannot execute -- the next test -- because
            // an external switch stops and waits for a person by definition.
            Execution execution = Executor.execute(
                    settled(Deployments.blueGreen()).console(
                            Console.unattended(line -> { })).rehearsal());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
        }
    }

    @Nested
    @DisplayName("the preflight")
    class Preflight {

        @Test
        void refusesAnExternalEndpointWhenNobodyIsWatching() {
            Execution execution = Executor.execute(settled(Deployments.blueGreen())
                    .console(Console.unattended(line -> { })).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.REFUSED);
            assertThat(blue.wrote).isEmpty();
            assertThat(green.wrote).isEmpty();
            assertThat(execution.render()).contains("nobody watching");
        }

        @Test
        void refusesACapabilityTheClusterDoesNotHave() {
            RecordingBroker one = new RecordingBroker("blue");
            RecordingBroker two = new RecordingBroker("green");
            Run run = Run.of(Deployments.blueGreen())
                    .from(new Run.Side("blue",
                            org.acemq.infra.provider.ProbedCluster.named("blue")
                                    .cannot(org.acemq.infra.provider.Capability.DRAIN_BY_SHOVEL,
                                            "rabbitmq_shovel is not enabled on this cluster")
                                    .build(),
                            one, "amqp://blue:5672"))
                    .to(Deployments.side("green", two))
                    .console(new Voice(Console.Answer.PROCEED))
                    .cutover();

            Execution execution = Executor.execute(run);

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.REFUSED);
            assertThat(execution.render()).contains("rabbitmq_shovel is not enabled");
            assertThat(one.wrote).isEmpty();
        }

        @Test
        void refusesADrainWhosePatternsSelectNothing() {
            DeploymentFile file = Deployments.file(Deployments.BLUE_GREEN
                    .replace("queues: [\"orders.*\", \"!orders.audit\"]",
                            "queues: [\"billing.*\"]"));

            Execution execution = Executor.execute(settled(file).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.REFUSED);
            assertThat(execution.render()).contains("select nothing");
        }

        @Test
        void refusesAGuardWhoseTimeoutIsShorterThanTheStatisticsInterval() {
            DeploymentFile file = Deployments.file(
                    Deployments.BLUE_GREEN.replace("timeout: 15m", "timeout: 3s"));

            Execution execution = Executor.execute(settled(file).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.REFUSED);
            assertThat(execution.render()).contains("collect_statistics_interval");
        }

        @Test
        void refusesBeforeAnythingIsWrittenRatherThanAtTheStep() {
            // The point of a preflight. Any of these findings would also surface at the step that
            // hit it -- with the drain done and the source empty.
            DeploymentFile file = Deployments.file(
                    Deployments.BLUE_GREEN.replace("on: green", "on: purple"));

            Execution execution = Executor.execute(settled(file).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.REFUSED);
            assertThat(execution.render()).contains("'purple'");
            assertThat(blue.wrote).isEmpty();
            assertThat(green.wrote).isEmpty();
        }
    }

    @Nested
    @DisplayName("a guard")
    class Guarding {

        @Test
        void isNotSatisfiedByOneStaleZero() {
            // The hazard scripts/blue-green-lab.sh found and wrote down: the management API's
            // depths refresh on collect_statistics_interval, so the first zero a drain produces is
            // very often a zero from before the messages arrived. One reading of nought followed
            // by the real number must not end the drain.
            Run.Builder builder = settled(Deployments.blueGreen());
            blue.whileDraining.add(RecordingBroker.idle());
            blue.whileDraining.add(RecordingBroker.backlog(27412));
            blue.whileDraining.add(RecordingBroker.idle());

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
            // The drain's guard had to see a run of zeroes spanning the settle window, so the
            // stale one at the front bought it nothing: the real number arrived next and the run
            // started again. Five readings at five seconds apart is four waits.
            assertThat(blue.read).filteredOn(one -> one.startsWith("measure:orders"))
                    .hasSizeGreaterThanOrEqualTo(5);
        }

        @Test
        void abortsWhenItsConditionCannotBeObservedEvenIfOnTimeoutSaysContinue() {
            DeploymentFile file = Deployments.file(
                    Deployments.BLUE_GREEN.replace("onTimeout: prompt", "onTimeout: continue"));
            Run.Builder builder = settled(file);
            blue.readings.clear();
            blue.readings.add(new Observation(Reading.of(0), Reading.of(0), Reading.of(1),
                    Reading.unobservable("the metrics collector is disabled")));

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.ABORTED);
            // onTimeout answers "the condition did not come true in the time allowed". Nothing
            // here came true or failed to: the reading was never taken, and walking past a guard
            // nobody evaluated is the failure the third verdict exists to prevent.
            assertThat(line(execution, "pause-producers")).contains("cannot be observed");
        }

        @Test
        void stopsRatherThanPromptsWhenNobodyIsThere() {
            // A hook endpoint rather than an external one, so that the preflight lets an unattended
            // run start at all — the point being tested is what a prompt does, not what an external
            // switch does.
            DeploymentFile file = Deployments.file(Deployments.BLUE_GREEN
                    .replace("  kind: external", "  kind: hook")
                    .replace("  description: orders-amqp.internal is a CNAME switched by the"
                            + " platform team.", "  run: /usr/bin/true"));
            Run.Builder builder = settled(file).console(Console.unattended(line -> { }));
            blue.readings.clear();
            blue.readings.add(RecordingBroker.publishing(12));

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.STOPPED);
            assertThat(line(execution, "pause-producers"))
                    .contains("prompt in an unattended run is not a prompt");
        }
    }

    @Nested
    @DisplayName("an abort")
    class Aborting {

        @Test
        void tearsDownTheMovementThisRunDeclared() {
            // A shovel goes on moving messages after the process that declared it stops looking.
            // Left alone, the source quietly empties while the report says the cutover stopped.
            Run.Builder builder = settled(Deployments.blueGreen());
            green.movementsFinish = false;
            blue.whileDraining.add(RecordingBroker.backlog(27412));

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.ABORTED);
            assertThat(green.wrote).anyMatch(one -> one.startsWith("cancel:"));
            assertThat(execution.movements()).isEmpty();
        }

        @Test
        void namesWhatItCouldNotTearDown() {
            Run.Builder builder = settled(Deployments.blueGreen());
            green.movementsFinish = false;
            green.cancelFails = true;
            blue.whileDraining.add(RecordingBroker.backlog(27412));

            Execution execution = Executor.execute(builder.cutover());

            // The line somebody has to act on: a shovel is still moving messages off the source
            // and the run that declared it has stopped looking.
            assertThat(execution.movements()).hasSize(1);
            assertThat(execution.render()).contains("STILL DECLARED");
        }

        @Test
        void leavesTheLaterStepsUnattempted() {
            Run.Builder builder = settled(Deployments.blueGreen());
            green.movementsFinish = false;
            blue.whileDraining.add(RecordingBroker.backlog(27412));

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.steps()).filteredOn(step -> step.id().equals("switch-endpoint"))
                    .allMatch(step -> step.status() == Execution.Status.NOT_REACHED);
        }

        @Test
        void derivesARollbackForOnlyTheStepsThatRan() {
            // A cutover that never drained has moved nothing, so a rollback that drained the
            // target back to the source would declare a shovel on a cluster that does not need
            // one. The derivation follows what happened, not what was planned.
            DeploymentFile file = Deployments.file(
                    Deployments.BLUE_GREEN.replace("onTimeout: prompt", "onTimeout: abort"));
            Run.Builder builder = settled(file);
            blue.readings.clear();
            blue.readings.add(RecordingBroker.publishing(12));

            Execution execution = Executor.execute(builder.cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.ABORTED);
            assertThat(execution.done().stream().map(Execution.Taken::id))
                    .doesNotContain("drain-messages");
            assertThat(execution.rollback()).isNotNull();
            assertThat(green.wrote).noneMatch(one -> one.startsWith("drain:"));
        }
    }

    @Nested
    @DisplayName("the backup")
    class Backup {

        @Test
        void writesTheSourceDefinitionsBeforeAnythingIsTouched(@TempDir Path directory) {
            Path file = directory.resolve("orders.json");
            DeploymentFile deployment = Deployments.file(Deployments.BLUE_GREEN
                    .replace("    enabled: false",
                            "    enabled: true\n    path: " + file));

            Execution execution = Executor.execute(settled(deployment).cutover());

            assertThat(execution.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
            assertThat(file).exists();
            assertThat(execution.steps().get(0).id()).isEqualTo("backup");
            assertThat(execution.steps().get(0).number()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the sealed Action type")
    class EveryAction {

        @Test
        void hasAnAnswerInTheExecutorForEveryOneOfItsSubclasses() {
            // Action was sealed so that a switch over it could be exhaustive, and the executor
            // cannot use one: pattern matching for switch is not final until Java 21 and this
            // compiles at 17. So the guarantee is recovered here. An eighth action added in phase
            // 3 breaks this test, which is what the sealing was for.
            assertThat(Action.class.getPermittedSubclasses()).hasSize(7);
            List<String> handled = List.of("Requires", "CopyTopology", "Announce",
                    "CloseConnections", "Drain", "Mirror", "Switch");
            for (Class<?> permitted : Action.class.getPermittedSubclasses()) {
                assertThat(handled).contains(permitted.getSimpleName());
            }
        }
    }

    private static String line(Execution execution, String id) {
        return execution.steps().stream().filter(step -> step.id().equals(id))
                .flatMap(step -> step.lines().stream())
                .reduce("", (all, one) -> all + " " + one);
    }
}
