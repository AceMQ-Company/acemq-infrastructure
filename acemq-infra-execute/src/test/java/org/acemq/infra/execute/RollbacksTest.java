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

import java.util.List;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Getting back, worked out without a broker.
 *
 * <p>A rollback is not an undo and these are the cases where the difference shows: a cutover that
 * stopped before the drain has nothing to bring back, a topology copy has no inverse worth
 * performing, and the one thing that does invert — the shovel — inverts into a second cutover in
 * the other direction with all of the first one's costs.
 */
class RollbacksTest {

    /** The same file without its written rollback, so the derivation has to do the work. */
    private static DeploymentFile withoutAWrittenRollback() {
        return Deployments.file(Deployments.BLUE_GREEN);
    }

    private static List<Step> stepsOf(DeploymentFile file, String... ids) {
        return file.deployment().orElseThrow().stepsOrEmpty().stream()
                .filter(step -> List.of(ids).contains(step.describeId()))
                .toList();
    }

    @Nested
    @DisplayName("derived from a run that completed")
    class Completed {

        @Test
        void switchesTheEndpointBackBeforeItDrainsAnything() {
            DeploymentFile file = withoutAWrittenRollback();
            List<Step> undo = Rollbacks.derive(file,
                    stepsOf(file, "topology", "drain-messages", "switch-endpoint"),
                    "blue", "green");

            // Bringing the clients back before the messages would hand them a cluster that is
            // empty and stays empty until the drain-back runs, which is a visible outage rather
            // than a silent one. docs/blue-green.md's own example is in this order.
            assertThat(undo.stream().map(Step::describeId))
                    .containsExactly("switch-endpoint", "drain-back");
        }

        @Test
        void drainsTheOtherWayWithTheSameQueues() {
            DeploymentFile file = withoutAWrittenRollback();
            List<Step> undo = Rollbacks.derive(file, stepsOf(file, "drain-messages"),
                    "blue", "green");

            Action.Drain back = undo.get(0).find(Action.Drain.class).orElseThrow();
            assertThat(back.from()).contains("green");
            assertThat(back.to()).contains("blue");
            assertThat(back.queues()).containsExactly("orders.*", "!orders.audit");
        }

        @Test
        void waitsOnTheClusterItIsEmptying() {
            DeploymentFile file = withoutAWrittenRollback();
            List<Step> undo = Rollbacks.derive(file, stepsOf(file, "drain-messages"),
                    "blue", "green");

            assertThat(undo.get(0).waitFor()).isPresent();
            assertThat(undo.get(0).waitFor().orElseThrow().on()).contains("green");
            assertThat(undo.get(0).waitFor().orElseThrow().depth()).hasValue(0);
        }

        @Test
        void switchesBackToTheClusterTheFileSaidToKeep() {
            DeploymentFile file = Deployments.file(Deployments.BLUE_GREEN
                    + "\nrollback:\n  keep: blue\n  for: 72h\n");
            List<Step> undo = Rollbacks.derive(file, stepsOf(file, "switch-endpoint"),
                    "blue", "green");

            assertThat(undo.get(0).find(Action.Switch.class).orElseThrow().target())
                    .contains("blue");
        }
    }

    @Nested
    @DisplayName("derived from a run that stopped partway")
    class Partway {

        @Test
        void hasNothingToUndoWhenNothingMoved() {
            DeploymentFile file = withoutAWrittenRollback();

            assertThat(Rollbacks.derive(file, stepsOf(file, "topology", "announce-drain"),
                    "blue", "green")).isEmpty();
            assertThat(Rollbacks.anythingToUndo(stepsOf(file, "topology", "announce-drain")))
                    .isFalse();
        }

        @Test
        void doesNotInvertATopologyCopy() {
            // The cluster being returned to is the one the shape was read from, so it already has
            // it -- and deleting the target's queues would destroy whatever was published to it
            // during the window. There is nothing to undo and the destructive option is the one
            // that looks like an undo.
            DeploymentFile file = withoutAWrittenRollback();
            List<Step> undo = Rollbacks.derive(file,
                    stepsOf(file, "topology", "policies", "drain-messages"), "blue", "green");

            assertThat(undo.stream().map(Step::describeId)).containsExactly("drain-back");
        }

        @Test
        void doesNotInventAWayToReopenAClosedConnection() {
            DeploymentFile file = withoutAWrittenRollback();

            assertThat(Rollbacks.derive(file, stepsOf(file, "drain-consumers"), "blue", "green"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what the file wrote")
    class Written {

        @Test
        void winsOverAnythingThatCouldBeDerived() {
            // Somebody who has thought about the rollback beforehand has better information than
            // this derivation does. A default is for the case nobody thought about.
            DeploymentFile file = Deployments.file(Deployments.BLUE_GREEN + """

                    rollback:
                      keep: blue
                      for: 72h
                      steps:
                        - id: shout
                          announce: {}
                    """);

            assertThat(Rollbacks.derive(file, List.of(), "blue", "green").stream()
                    .map(Step::describeId)).containsExactly("shout");
        }
    }

    @Nested
    @DisplayName("the notes")
    class Notes {

        @Test
        void sayThatTheSourceIsEmptyAndThatHeadersHaveBeenLostTwice() {
            DeploymentFile file = withoutAWrittenRollback();
            List<String> notes = Rollbacks.notes(stepsOf(file, "drain-messages"), "blue");

            assertThat(notes).anyMatch(note -> note.contains("blue's queues are empty"));
            assertThat(notes).anyMatch(note -> note.contains("republished twice"));
        }

        @Test
        void sayNothingAboutADrainThatDidNotHappen() {
            DeploymentFile file = withoutAWrittenRollback();

            assertThat(Rollbacks.notes(stepsOf(file, "topology"), "blue")).isEmpty();
        }
    }
}
