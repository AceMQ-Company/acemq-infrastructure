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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.acemq.infra.execute.Broker;
import org.acemq.infra.execute.Observation;
import org.acemq.infra.execute.Observation.Reading;
import org.acemq.infra.execute.Topology;

/**
 * A cluster that exists only to say what was done to it.
 *
 * <p>The command is the subject here rather than the executor, so this is deliberately the
 * simplest broker that answers every verb: what the suite asks of it is which verbs were reached
 * and, far more often, that none of the writing ones were. {@code acemq-infra-execute}'s own suite
 * is where step sequencing and guard arithmetic are tested, against a broker of its own.
 */
final class Recorder implements Broker {

    private final String name;

    /** Every writing verb, in order. The assertion most of these tests make is that it is empty. */
    final List<String> wrote = new ArrayList<>();

    /** Every reading verb, in order, which is how a rehearsal is shown to have probed at all. */
    final List<String> read = new ArrayList<>();

    Recorder(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Topology snapshotTopology(Scope scope) {
        read.add("snapshotTopology");
        return new Shape(name);
    }

    @Override
    public void applyTopology(Topology topology, Scope scope) {
        wrote.add("applyTopology");
    }

    @Override
    public List<Attachment> listAttachments() {
        read.add("listAttachments");
        return List.of(new Attachment("10.0.0.1:52000", "orders-service", true));
    }

    @Override
    public void detach(Attachment attachment, String reason) {
        wrote.add("detach:" + attachment.name());
    }

    @Override
    public Movement drain(Drainage drainage) {
        wrote.add("drain:" + String.join(",", drainage.queues()));
        return new Movement(drainage.label(), name, drainage.queues());
    }

    @Override
    public Movement mirror(Mirroring mirroring) {
        wrote.add("mirror");
        return new Movement(mirroring.label(), name, List.of("upstream " + mirroring.label()));
    }

    @Override
    public boolean finished(Movement movement) {
        read.add("finished");
        return true;
    }

    @Override
    public void cancel(Movement movement) {
        wrote.add("cancel");
    }

    @Override
    public Observation measure(List<String> queues) {
        read.add("measure");
        // Everything readable and everything quiet, so that a rehearsal reports each guard as
        // satisfied right now rather than as unobservable, which is a different sentence.
        return new Observation(Reading.of(0), Reading.of(0), Reading.of(2), Reading.of(0));
    }

    @Override
    public boolean announce(Envelope envelope) {
        wrote.add("announce:" + envelope.exchange());
        return true;
    }

    @Override
    public void close() {
        read.add("close");
    }

    /** A topology that is a name and a sentence. */
    private record Shape(String from) implements Topology {

        @Override
        public String describe() {
            return "3 exchanges, 4 queues";
        }

        @Override
        public long writeTo(Path path, boolean redactCredentials) throws IOException {
            Files.writeString(path, "{\"from\":\"" + from + "\"}");
            return Files.size(path);
        }
    }
}
