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

import java.util.List;
import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * The {@code deployment:} block — what is being done, between which clusters, and how.
 *
 * <p>Two absences in this record mean different things and both are load-bearing.
 *
 * <p>{@code semantics} absent is an error for a cutover, because there is no default and the tool
 * will not choose between "a message may be processed twice" and "a message may be stranded" on
 * somebody's behalf.
 *
 * <p>{@code steps} absent is not an error. The default list for the operation is filled in and
 * printed in full, so that it can be pasted into the file and edited — a starting point rather
 * than a hidden behaviour. An <em>empty</em> list is a different thing again and is refused, which
 * is why this is an {@code Optional<List<Step>>} and not a list that is empty when the key was
 * missing.
 *
 * @param operation blueGreen, canary or mirror
 * @param from the cluster being moved away from
 * @param to the cluster being moved to
 * @param semantics which way the ambiguous window falls; none for a mirror
 * @param scope what a canary's single workload consists of
 * @param mirror what a mirror copies
 * @param backup what is captured before anything is touched
 * @param announce the envelope published to tell clients something is happening
 * @param steps the explicit step list, or empty when the default should be filled in
 * @param unknownKeys keys the model has no field for, kept for the validator to report
 * @param location where the {@code deployment:} block is written
 */
public record Deployment(Optional<Operation> operation, Optional<String> from, Optional<String> to,
                         Optional<Semantics> semantics, Optional<Scope> scope,
                         Optional<MirrorSpec> mirror, Optional<Backup> backup,
                         Optional<Announce> announce, Optional<List<Step>> steps,
                         List<UnknownKey> unknownKeys, Location location) {

    /** The steps as written, or none when the file left them to the default list. */
    public List<Step> stepsOrEmpty() {
        return steps.orElse(List.of());
    }

    /**
     * What a canary moves.
     *
     * <p>{@code services} is not decoration. The plan asserts that every consumer of the scoped
     * queues belongs to one of these services and refuses if something else is attached — moving a
     * queue without one of its consumers is the partition the whole operation is arranged to
     * avoid.
     *
     * @param vhost the vhost the scope lives in
     * @param queues the queues that move, and everything attached to them
     * @param services the services allowed to be consuming them
     * @param unknownKeys keys the model has no field for; {@code percentage} arrives here
     * @param location where {@code scope:} is written
     */
    public record Scope(Optional<String> vhost, List<String> queues, List<String> services,
                        List<UnknownKey> unknownKeys, Location location) {
    }

    /**
     * What is captured before the cutover touches anything.
     *
     * <p>A block rather than the boolean the old format had, because the file it writes is a
     * definitions document and a definitions document carries password hashes. Redaction is the
     * default, which means the backup is not by itself enough to restore a cluster — a real
     * trade-off, and one the tool states when it writes the file rather than leaving it to be
     * discovered during a restore.
     *
     * @param enabled whether to take one at all
     * @param path where to write it, with {@code {{name}}} and {@code {{timestamp}}} substituted
     * @param redactCredentials whether to strip the password hashes; on by default
     * @param location where {@code backup:} is written
     */
    public record Backup(Optional<Boolean> enabled, Optional<String> path,
                         Optional<Boolean> redactCredentials, Location location) {
    }

    /**
     * The announcement envelope: exchange, routing key, payload.
     *
     * <p>Kept from the old format unchanged. It is how the applications find out a deployment has
     * started from the broker they are already connected to, rather than from a channel nobody is
     * watching.
     *
     * @param exchange where to publish
     * @param routingKey under what key
     * @param payload the body
     * @param location where {@code announce:} is written
     */
    public record Announce(Optional<String> exchange, Optional<String> routingKey,
                           Optional<String> payload, Location location) {
    }
}
