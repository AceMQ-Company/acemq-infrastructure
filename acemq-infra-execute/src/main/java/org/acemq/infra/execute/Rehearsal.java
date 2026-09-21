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

import java.util.List;

/**
 * A broker with the writing taken off it, for the duration of a rehearsal.
 *
 * <p>The same idea as {@code ReadOnlyAdmin} one layer down, and here for the same reason. A
 * rehearsal — what {@code --dry-run} will be — is worth nothing if it is a mode each step has to
 * remember to honour, because the step that forgets is discovered by a production cluster. So the
 * mode is not consulted by the steps at all: {@link Executor} wraps both brokers in one of these
 * before the first step runs, and a writing verb reached from a rehearsal throws.
 *
 * <p>Throws, rather than quietly doing nothing. A rehearsal that silently swallowed a write would
 * report a step as having gone fine, which is the one thing a rehearsal exists to be trusted about.
 * The exception names the verb and says the run was a rehearsal, so the failure reads as a bug in
 * the executor — which is exactly what it would be.
 *
 * <p>The reading verbs pass straight through, because that is the whole value of a rehearsal over a
 * plan: it re-reads the live clusters and reports what each step <em>would</em> do at this moment
 * rather than at the moment the plan was written.
 */
final class Rehearsal implements Broker {

    private final Broker real;

    Rehearsal(Broker real) {
        this.real = real;
    }

    @Override
    public String name() {
        return real.name();
    }

    @Override
    public Topology snapshotTopology(Scope scope) {
        return real.snapshotTopology(scope);
    }

    @Override
    public List<Attachment> listAttachments() {
        return real.listAttachments();
    }

    @Override
    public Observation measure(List<String> queues) {
        return real.measure(queues);
    }

    @Override
    public boolean finished(Movement movement) {
        // A rehearsal never declared one, so nothing it holds a handle to can be running. Reading
        // through to the broker would answer about somebody else's movement.
        return true;
    }

    @Override
    public void applyTopology(Topology topology, Scope scope) {
        throw refuse("applyTopology");
    }

    @Override
    public void detach(Attachment attachment, String reason) {
        throw refuse("detach");
    }

    @Override
    public Movement drain(Drainage drainage) {
        throw refuse("drain");
    }

    @Override
    public Movement mirror(Mirroring mirroring) {
        throw refuse("mirror");
    }

    @Override
    public void cancel(Movement movement) {
        throw refuse("cancel");
    }

    @Override
    public boolean announce(Envelope envelope) {
        throw refuse("announce");
    }

    @Override
    public void close() {
        real.close();
    }

    private IllegalStateException refuse(String verb) {
        return new IllegalStateException("this run is a rehearsal and something asked " + name()
                + " to " + verb + ". A rehearsal writes nothing, so reaching this is a bug in the"
                + " executor rather than a problem with the estate — the step should have reported"
                + " what it would do instead of doing it.");
    }
}
