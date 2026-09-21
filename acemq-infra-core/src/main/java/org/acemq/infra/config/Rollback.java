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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * The {@code rollback:} block — which cluster stays cold, for how long, and what undoing looks
 * like.
 *
 * <p>Undoing is not a switch back. Once the drain has run, blue's queues are empty, so the
 * rollback is a drain in the other direction and it carries the same costs the first one did: the
 * messages are republished a second time, and {@code x-delivery-count} and {@code x-death} are
 * lost again.
 *
 * <p>A mirror has no rollback, because nothing happened.
 *
 * @param keep which cluster stays up and untouched
 * @param keepFor how long it stays; the file writes this as {@code for:}, which is not a name a
 *     Java accessor can have
 * @param steps how to undo, or empty when the default list for the operation should be used
 * @param location where the {@code rollback:} block is written
 */
public record Rollback(Optional<String> keep, Optional<Duration> keepFor,
                       Optional<List<Step>> steps, Location location) {

    /** The steps as written, or none when the file left them to the default list. */
    public List<Step> stepsOrEmpty() {
        return steps.orElse(List.of());
    }
}
