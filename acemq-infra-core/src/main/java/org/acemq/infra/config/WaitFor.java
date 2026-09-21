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
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.yaml.Location;

/**
 * A guard: the condition a step waits on, how long it waits, and what happens when it does not
 * come true.
 *
 * <p>Per step rather than one global timeout, which is the change from {@code guards:} in the old
 * format. The conditions that matter differ by step — a pause waits on the publish rate, a
 * consumer close waits on unacked reaching zero, a drain waits on depth — and a single number
 * across all of them is a number that is wrong for most of them.
 *
 * <p>Whether a condition is <em>present</em> is itself load-bearing and not only its value:
 * {@code publishRate: 0} and {@code unacked: 0} are what the validator looks for before a drain,
 * because a drain with nothing settled first shovels messages consumers were still holding. So
 * these are {@link OptionalInt} rather than defaulted to zero — a file that did not ask must not
 * look like a file that asked for zero.
 *
 * @param on which cluster to measure
 * @param publishRate wait until the source stops being published to
 * @param unacked wait until nothing is held unacknowledged
 * @param depth wait until the queues are empty
 * @param consumers wait until the consumer count is within bounds
 * @param timeout how long to wait; a guard without one waits forever, which the validator refuses
 * @param onTimeout what to do when it expires
 * @param location where the {@code waitFor:} block is written
 */
public record WaitFor(Optional<String> on, OptionalInt publishRate, OptionalInt unacked,
                      OptionalInt depth, Optional<Consumers> consumers, Optional<Duration> timeout,
                      Optional<OnTimeout> onTimeout, Location location) {

    /**
     * Whether this guard waits for the source to settle.
     *
     * <p>Named because it is the precondition for a drain and not a general property: producers
     * stopped or deliveries acknowledged are the two facts that make "what is left" a fixed
     * quantity. Everything else a guard can wait for is true of a cluster that is still moving.
     */
    public boolean settlesTheSource() {
        return publishRate.isPresent() || unacked.isPresent();
    }

    /**
     * @param min the fewest consumers that must be attached
     * @param max the most that may be
     * @param location where the {@code consumers:} block is written
     */
    public record Consumers(OptionalInt min, OptionalInt max, Location location) {
    }
}
