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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.Step;
import org.acemq.infra.execute.Fakes.RecordingBroker;
import org.acemq.infra.execute.Fakes.Voice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal: what {@code apply} leaves behind so that {@code rollback} can be run by a process
 * that did not run the cutover, and every reason {@code rollback} has to refuse one.
 */
class JournalTest {

    private static final byte[] BYTES = Deployments.BLUE_GREEN.getBytes(StandardCharsets.UTF_8);

    private static final Map<String, String> CLUSTERS =
            Map.of("blue", "https://blue:15671", "green", "https://green:15671");

    @TempDir
    private Path directory;

    private Journal begin(Path path) {
        return Journal.begin(path, "acemq-infra test", "orders", directory.resolve("orders.yaml"),
                BYTES, "blue", "green", CLUSTERS);
    }

    private Execution cutover(DeploymentFile file, Journal journal) {
        RecordingBroker blue = new RecordingBroker("blue");
        RecordingBroker green = new RecordingBroker("green");
        blue.readings.add(RecordingBroker.idle());
        green.readings.add(RecordingBroker.idle());
        blue.attachments.add(new Broker.Attachment("10.0.0.1:52000", "orders-service", true));
        return Executor.execute(Run.of(file)
                .from(Deployments.side("blue", blue))
                .to(Deployments.side("green", green))
                .timing(new Fakes.Ticks())
                .console(new Voice(Console.Answer.PROCEED))
                .journal(journal)
                .cutover());
    }

    @Test
    @DisplayName("records a cutover so that the rollback read back is the one the run derived")
    void theSameRollbackAsTheRun() {
        DeploymentFile file = Deployments.blueGreen();
        Path path = directory.resolve("journal.json");
        Execution execution = cutover(file, begin(path));
        assertThat(execution.ok()).isTrue();

        Journal read = Journal.read(path);
        assertThat(read.outcome()).isEqualTo("completed");
        List<Step> completed = read.completed(Run.stepsOf(file));
        assertThat(Rollbacks.derive(file, completed, read.from(), read.to()))
                .isEqualTo(execution.rollback());
        assertThat(read.interrupted()).isEmpty();
        // The drain declared a shovel, and a rollback has to be able to ask whether it is gone.
        assertThat(read.movements()).isNotEmpty();
        assertThat(read.mismatch(BYTES, "blue", "green", CLUSTERS)).isEmpty();
    }

    @Test
    @DisplayName("a rehearsal records nothing, because it did nothing")
    void aRehearsalRecordsNothing() throws IOException {
        DeploymentFile file = Deployments.blueGreen();
        Path path = directory.resolve("journal.json");
        Journal journal = begin(path);
        String before = Files.readString(path);
        Executor.execute(Run.of(file)
                .from(Deployments.side("blue", new RecordingBroker("blue")))
                .to(Deployments.side("green", new RecordingBroker("green")))
                .journal(journal)
                .rehearsal());
        assertThat(Files.readString(path)).isEqualTo(before);
    }

    @Test
    @DisplayName("counts a step a crash left at 'started' as having happened")
    void aCrashMidStep() {
        DeploymentFile file = Deployments.blueGreen();
        List<Step> steps = Run.stepsOf(file);
        int drain = indexOf(steps, "drain-messages");
        Path path = directory.resolve("journal.json");
        Journal journal = begin(path);
        // What a process killed during the drain leaves: the step started, nothing after it.
        journal.starting(5, drain, "drain-messages");

        Journal read = Journal.read(path);
        assertThat(read.outcome()).isEqualTo("running");
        assertThat(read.interrupted()).containsExactly("drain-messages");
        assertThat(read.completed(steps)).extracting(Step::describeId)
                .containsExactly("drain-messages");
    }

    @Test
    @DisplayName("does not count a failed step, which the executor already stood down")
    void aFailedStepIsNotUndone() {
        DeploymentFile file = Deployments.blueGreen();
        List<Step> steps = Run.stepsOf(file);
        int drain = indexOf(steps, "drain-messages");
        Path path = directory.resolve("journal.json");
        Journal journal = begin(path);
        journal.starting(5, drain, "drain-messages");
        journal.taken(drain, new Execution.Taken(5, "drain-messages", Execution.Status.FAILED,
                List.of("failed: no")));
        journal.finish(Execution.Outcome.ABORTED);

        assertThat(Journal.read(path).completed(steps)).isEmpty();
    }

    @Test
    @DisplayName("never overwrites an earlier journal")
    void neverOverwrites() throws IOException {
        Path path = directory.resolve("journal.json");
        Files.writeString(path, "the only record of an earlier run");
        assertThatThrownBy(() -> begin(path)).hasMessageContaining("already exists");
        assertThat(Files.readString(path)).isEqualTo("the only record of an earlier run");
    }

    @Test
    @DisplayName("refuses a journal written by a newer format, rather than guessing at it")
    void newerFormat() throws IOException {
        Path path = directory.resolve("journal.json");
        begin(path);
        Files.writeString(path, Files.readString(path)
                .replace("\"format\" : 1", "\"format\" : " + (Journal.FORMAT + 1)));
        assertThatThrownBy(() -> Journal.read(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("journal format " + (Journal.FORMAT + 1))
                .hasMessageContaining("newer acemq-infra");
    }

    @Test
    @DisplayName("refuses a file that is not a journal at all")
    void notAJournal() throws IOException {
        Path path = directory.resolve("journal.json");
        Files.writeString(path, "{\"something\": \"else\"}");
        assertThatThrownBy(() -> Journal.read(path)).hasMessageContaining("not an acemq-infra"
                + " journal");
    }

    @Test
    @DisplayName("notices a deployment file that changed, and clusters that moved")
    void anotherPlan() {
        Path path = directory.resolve("journal.json");
        begin(path);
        Journal read = Journal.read(path);

        assertThat(read.mismatch("something else".getBytes(StandardCharsets.UTF_8), "blue",
                "green", CLUSTERS)).get().asString().contains("not the one this journal");
        assertThat(read.mismatch(BYTES, "green", "blue", CLUSTERS)).get().asString()
                .contains("blue → green");
        assertThat(read.mismatch(BYTES, "blue", "green",
                Map.of("blue", "https://elsewhere:15671", "green", "https://green:15671")))
                .get().asString().contains("cluster 'blue'");
    }

    @Test
    @DisplayName("refuses a step list that does not line up with the journal")
    void stepsThatDoNotLineUp() {
        DeploymentFile file = Deployments.blueGreen();
        Path path = directory.resolve("journal.json");
        cutover(file, begin(path));
        assertThatThrownBy(() -> Journal.read(path).completed(Run.stepsOf(file).subList(0, 2)))
                .hasMessageContaining("not the plan this journal was written from");
    }

    @Test
    @DisplayName("is marked rolled back before the rollback writes anything, so it cannot run twice")
    void onlyOnce() {
        Path path = directory.resolve("journal.json");
        begin(path).finish(Execution.Outcome.COMPLETED);
        Journal read = Journal.read(path);
        assertThat(read.rolledBack()).isEmpty();

        read.rollback();
        assertThat(Journal.read(path).rolledBack()).get().asString()
                .contains("a rollback of this journal was started");
    }

    @Test
    @DisplayName("is replaced whole on every write, and leaves no temporary file behind")
    void atomically() throws IOException {
        Path path = directory.resolve("journal.json");
        cutover(Deployments.blueGreen(), begin(path));
        try (var listing = Files.list(directory)) {
            assertThat(listing.map(Path::getFileName).map(Path::toString))
                    .containsExactly("journal.json");
        }
    }

    @Test
    @DisplayName("copies every write to a mirror, a rollback's too, and refuses to begin without one")
    void mirrored() throws IOException {
        Path path = directory.resolve("journal.json");
        List<String> copies = new java.util.ArrayList<>();
        Journal journal = Journal.begin(path, "acemq-infra test", "orders",
                directory.resolve("orders.yaml"), BYTES, "blue", "green", CLUSTERS, copies::add);
        cutover(Deployments.blueGreen(), journal);
        // The copy is the file, every time: the last one is what is on disk.
        assertThat(copies).hasSizeGreaterThan(2);
        assertThat(copies.get(copies.size() - 1)).isEqualTo(Files.readString(path));

        int before = copies.size();
        Journal.read(path, copies::add).rollback();
        assertThat(copies).hasSize(before + 1);
        assertThat(copies.get(before)).contains("\"rollback\"");

        Journal.Mirror unreachable = json -> {
            throw new IOException("the API server said no");
        };
        assertThatThrownBy(() -> Journal.begin(directory.resolve("other.json"), "t", "orders",
                directory.resolve("orders.yaml"), BYTES, "blue", "green", CLUSTERS, unreachable))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("the API server said no");
    }

    private static int indexOf(List<Step> steps, String id) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).describeId().equals(id)) {
                return index;
            }
        }
        throw new AssertionError("no step " + id);
    }
}
