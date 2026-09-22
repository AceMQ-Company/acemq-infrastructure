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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What {@code streams.restartAt} says: one answer for everything, or one answer per stream.
 *
 * <p>The field is a confirmation rather than a setting — nothing in this tool can move a consumer's
 * offset — and a single scalar could only confirm something about an estate that agrees with
 * itself. An estate where {@code orders.events} consumers ask for {@code next} and
 * {@code audit.events} consumers ask for {@code first} has no scalar that is true of it, so no
 * honest file could be written for it at all and the operator's only way past the refusal was to
 * stop running the tool. That is a real hole rather than an inconvenience, and it is why there is
 * a mapping.
 *
 * <h2>Why the stream is the finest key</h2>
 *
 * <p>docs/message-state.md used to say "per stream, or per consumer group", and the second half of
 * that does not survive contact with the broker. RabbitMQ does have a notion of a group — the
 * single-active-consumer feature groups the consumers that share a stream <em>and a name</em> — but
 * the name is a stream-protocol concept, and what this tool reads is {@code /api/consumers}, which
 * carries the queue, the channel and the consumer's arguments and no name of that kind. A consumer
 * here is queue plus connection plus user, exactly as the canary's scope check established it.
 *
 * <p>The user is the only sub-stream identity there is, and it is not a group: it is an
 * authentication identity, and two processes authenticating as {@code audit-writer} can and do ask
 * for different offsets. A key that can hold two different answers cannot be the key an
 * acknowledgement is written against, so keying on it would give the file a grouping the estate
 * does not actually have. The stream is where the answers stop ambiguating, so the stream is where
 * the mapping stops. One stream whose own consumers disagree with each other therefore still
 * cannot be written down — that file is refused, with the disagreement named, and the fix is to
 * make the consumers agree rather than to invent a finer key here.
 *
 * @param everywhere a single answer covering every stream in scope, as the file wrote it
 * @param byStream an answer per stream name, in the order the file wrote them; present and empty
 *     for a mapping that names nothing, which is a file that confirms nothing
 */
public record RestartAt(Optional<String> everywhere, Optional<Map<String, String>> byStream) {

    private static final RestartAt NONE = new RestartAt(Optional.empty(), Optional.empty());

    public RestartAt {
        if (everywhere.isPresent() && byStream.isPresent()) {
            throw new IllegalArgumentException("restartAt is a value or a mapping, never both");
        }
        byStream = byStream.map(entries ->
                Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    /** A file that did not write the field, which asks for nothing to be checked. */
    public static RestartAt none() {
        return NONE;
    }

    /**
     * One answer for every stream in scope, which is how the field has always been written.
     *
     * @param value the position, as the file wrote it
     * @return the acknowledgement
     */
    public static RestartAt everywhere(String value) {
        return new RestartAt(Optional.of(value), Optional.empty());
    }

    /**
     * One answer per stream.
     *
     * @param byStream stream name to position, in the order the file wrote them
     * @return the acknowledgement
     */
    public static RestartAt perStream(Map<String, String> byStream) {
        return new RestartAt(Optional.empty(), Optional.of(byStream));
    }

    /** Whether the file said anything at all, which is what makes it something to check. */
    public boolean written() {
        return everywhere.isPresent() || byStream.isPresent();
    }

    /** Whether it was written as a mapping, in which case a stream it does not name is a gap. */
    public boolean perStream() {
        return byStream.isPresent();
    }

    /** The streams the mapping names, empty for a scalar, for reporting the ones out of scope. */
    public Set<String> streams() {
        return byStream.orElseGet(Map::of).keySet();
    }

    /**
     * The answer that covers one stream.
     *
     * @param stream the stream's name
     * @return what the file says its consumers are configured to do, empty when the file says
     *     nothing about this stream — which is a scalar-free mapping's gap and never a default
     */
    public Optional<String> forStream(String stream) {
        return everywhere.or(() -> byStream.flatMap(entries ->
                Optional.ofNullable(entries.get(stream))));
    }
}
