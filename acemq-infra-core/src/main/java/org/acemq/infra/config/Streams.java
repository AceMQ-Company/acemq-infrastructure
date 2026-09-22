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
package org.acemq.infra.config;

import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * The {@code streams:} block — the operator's acknowledgement of what a cutover does to stream
 * consumers.
 *
 * <p>Stream offsets do not travel between clusters. RabbitMQ has no mechanism for moving them, so
 * the tool's whole contribution is to say what will happen to each consumer and make somebody
 * confirm it before the plan proceeds.
 *
 * <p>Phase 1 modelled only {@code acknowledged}, because that was the only field any page named.
 * docs/message-state.md does write the block out — {@code acknowledged}, {@code restartAt} and
 * {@code note} — and phase 3 is where the projection that uses the other two arrives, so they are
 * modelled here now.
 *
 * <p>{@code restartAt} does <strong>not</strong> set anything. Nothing in this tool can: an offset
 * is a position in a log and the position a consumer resumes from is the {@code x-stream-offset}
 * its own client asked for. So the field is a statement of what the operator believes the
 * consumers are configured to do, and its whole value is that the plan can hold it up against what
 * the consumers actually asked for and say when the two disagree. A field that looked like a
 * setting and was in fact a belief would be worse than no field, which is why it says so here and
 * in the plan output rather than only in the documentation.
 *
 * <p>It is a {@link RestartAt} rather than a string because one estate can owe two different
 * answers — {@code orders.events} accepting a gap while {@code audit.events} replays the log — and
 * a scalar could not describe that estate at all. The scalar spelling still means what it always
 * meant, and which of the two a file wrote is the difference between a stream nobody mentioned
 * being covered and it being a refusal.
 *
 * @param acknowledged whether the operator has confirmed what will happen to the offsets
 * @param restartAt where the operator believes the consumers will resume, for everything in scope
 *     or for one stream at a time; compared against each consumer's {@code x-stream-offset}, never
 *     written to anything
 * @param note why the gap or the replay is acceptable, carried into the plan so that the reviewer
 *     sees the reasoning beside the projection rather than in a ticket
 * @param location where the {@code streams:} block is written
 */
public record Streams(Optional<Boolean> acknowledged, RestartAt restartAt,
                      Optional<String> note, Location location) {

    /** Whether the file has said, in writing, that the restart positions are accepted. */
    public boolean confirmed() {
        return acknowledged.orElse(false);
    }
}
