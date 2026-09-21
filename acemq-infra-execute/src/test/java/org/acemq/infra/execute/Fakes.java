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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.acemq.infra.execute.Observation.Reading;

/**
 * A broker, a clock and a human, none of which exist.
 *
 * <p>The reason the executor's whole surface is interfaces is here. Step sequencing, guard
 * arithmetic, the settle window that keeps a stale zero from ending a drain, what an abort does to
 * a shovel it declared, and what an unattended prompt means — all of them are behaviours that
 * decide whether a cutover loses messages, and all of them are testable in milliseconds against
 * these. A suite that could only reach them through a container would reach two of them, slowly,
 * and leave the rest to somebody's production estate.
 */
final class Fakes {

    private Fakes() {
    }

    /** A cluster that records what was done to it. */
    static final class RecordingBroker implements Broker {

        private final String name;

        /** Every writing verb, in order, as {@code verb:detail}. The assertion most tests make. */
        final List<String> wrote = new ArrayList<>();

        /** Every reading verb, likewise. */
        final List<String> read = new ArrayList<>();

        /**
         * What {@code measure} answers when the guard is watching the whole virtual host, one per
         * call, the last repeating once they run out.
         */
        final Deque<Observation> readings = new ArrayDeque<>();

        /**
         * The same, for a guard watching a named set of queues.
         *
         * <p>Split from {@link #readings} because a drain's guard is the only one that names its
         * queues, and a test about the drain would otherwise have to count how many readings the
         * three guards before it had eaten.
         */
        final Deque<Observation> whileDraining = new ArrayDeque<>();

        /** Set to make {@code cancel} throw, for the abort that cannot tidy up after itself. */
        boolean cancelFails;

        /** The connections {@code listAttachments} reports. */
        final List<Attachment> attachments = new ArrayList<>();

        /** What {@code finished} answers about a movement. */
        boolean movementsFinish = true;

        /** Whether {@code announce} says the broker routed it anywhere. */
        boolean routes = true;

        /** Set to fail the next write, for the path where a step throws. */
        RuntimeException failWrites;

        RecordingBroker(String name) {
            this.name = name;
        }

        /** Everything observable and quiet: an estate with nothing happening on it. */
        static Observation idle() {
            return new Observation(Reading.of(0), Reading.of(0), Reading.of(1), Reading.of(0));
        }

        /** A cluster with a backlog on it and nothing publishing. */
        static Observation backlog(long depth) {
            return new Observation(Reading.of(depth), Reading.of(0), Reading.of(2), Reading.of(0));
        }

        /** A cluster somebody is still publishing to. */
        static Observation publishing(long rate) {
            return new Observation(Reading.of(0), Reading.of(0), Reading.of(2), Reading.of(rate));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Topology snapshotTopology(Scope scope) {
            read.add("snapshotTopology:" + scope.parts().size());
            return new FakeTopology(name);
        }

        @Override
        public void applyTopology(Topology topology, Scope scope) {
            write("applyTopology:" + scope.parts().size());
        }

        @Override
        public List<Attachment> listAttachments() {
            read.add("listAttachments");
            return List.copyOf(attachments);
        }

        @Override
        public void detach(Attachment attachment, String reason) {
            write("detach:" + attachment.name());
        }

        @Override
        public Movement drain(Drainage drainage) {
            write("drain:" + String.join(",", drainage.queues()));
            return new Movement(drainage.label(), name, drainage.queues());
        }

        @Override
        public Movement mirror(Mirroring mirroring) {
            write("mirror:" + String.join(",", mirroring.exchanges()));
            return new Movement(mirroring.label(), name, List.of("upstream " + mirroring.label()));
        }

        @Override
        public boolean finished(Movement movement) {
            read.add("finished:" + movement.label());
            return movementsFinish;
        }

        @Override
        public void cancel(Movement movement) {
            wrote.add("cancel:" + movement.label());
            if (cancelFails) {
                throw new IllegalStateException("the shovel could not be deleted");
            }
        }

        @Override
        public Observation measure(List<String> queues) {
            read.add("measure:" + String.join(",", queues));
            return next(queues.isEmpty() ? readings : whileDraining);
        }

        private static Observation next(Deque<Observation> scripted) {
            if (scripted.isEmpty()) {
                return idle();
            }
            return scripted.size() == 1 ? scripted.peek() : scripted.poll();
        }

        @Override
        public boolean announce(Envelope envelope) {
            write("announce:" + envelope.exchange());
            return routes;
        }

        @Override
        public void close() {
            read.add("close");
        }

        private void write(String what) {
            wrote.add(what);
            if (failWrites != null) {
                throw failWrites;
            }
        }
    }

    /** A topology that is a name and a sentence. */
    record FakeTopology(String from) implements Topology {

        @Override
        public String describe() {
            return "2 exchanges, 3 queues";
        }

        @Override
        public long writeTo(Path path, boolean redactCredentials) throws IOException {
            java.nio.file.Files.writeString(path, "{\"from\":\"" + from + "\"}");
            return java.nio.file.Files.size(path);
        }
    }

    /**
     * A clock that only moves when something waits.
     *
     * <p>Which is what makes a fifteen-minute timeout and a fifteen-second settle window testable
     * at all, and it is also more honest than a real one: the guard's behaviour depends on how much
     * time has passed between readings rather than on how long the test took to run.
     */
    static final class Ticks implements Timing {

        private Instant now = Instant.parse("2026-09-21T09:00:00Z");

        /** How much time this run has consumed, all of it in guards. */
        Duration elapsed = Duration.ZERO;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void pause(Duration duration) {
            now = now.plus(duration);
            elapsed = elapsed.plus(duration);
        }
    }

    /** A human who always gives the same answer, and remembers being asked. */
    static final class Voice implements Console {

        private final Answer answer;

        /** Every question, in order. */
        final List<String> asked = new ArrayList<>();

        /** Every line said. */
        final List<String> said = new ArrayList<>();

        Voice(Answer answer) {
            this.answer = answer;
        }

        @Override
        public void say(String line) {
            said.add(line);
        }

        @Override
        public Answer ask(String question) {
            asked.add(question);
            return answer;
        }
    }
}
