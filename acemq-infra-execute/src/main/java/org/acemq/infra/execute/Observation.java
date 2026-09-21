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

import java.util.Objects;

/**
 * What a cluster looked like, the moment it was measured.
 *
 * <p>Four readings, and every one of them is allowed to come back saying it could not be taken.
 * That is the point of the type and it is the reason {@code measure} does not simply return four
 * numbers: a guard is the thing standing between a cutover and a half-moved estate, and a provider
 * that reported nought where it meant "these credentials cannot see that" would turn the guard into
 * a formality that always passes.
 *
 * <p>docs/broker-agnostic.md says the same thing from the other end — that {@code measure} returns
 * a capability-tagged set of observations rather than a fixed record, because RabbitMQ's classic
 * and quorum queues have no consumer position and Kafka's offsets are the central fact of the
 * system. A reading that knows it is absent is the smallest honest version of that.
 *
 * @param depth how many messages the queues in scope hold, ready and unacknowledged together
 * @param unacked how many are held by a consumer and not yet settled
 * @param consumers how many consumers are attached
 * @param publishRate how many messages a second are arriving
 */
public record Observation(Reading depth, Reading unacked, Reading consumers, Reading publishRate) {

    public Observation {
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(unacked, "unacked");
        Objects.requireNonNull(consumers, "consumers");
        Objects.requireNonNull(publishRate, "publishRate");
    }

    /**
     * One number, or the reason there is no number.
     *
     * @param observed whether the reading could be taken at all
     * @param value the number, meaningless when it could not
     * @param whyNot what stopped it, empty when it was taken
     */
    public record Reading(boolean observed, long value, String whyNot) {

        /**
         * A number that was actually read.
         *
         * @param value what was read
         * @return the reading
         */
        public static Reading of(long value) {
            return new Reading(true, value, "");
        }

        /**
         * A number that could not be read, and the sentence a report should print instead.
         *
         * @param whyNot what stopped it — a permission, a plugin, a statistics collector that is
         *     switched off. Specific enough to act on
         * @return the reading
         */
        public static Reading unobservable(String whyNot) {
            return new Reading(false, 0, whyNot);
        }

        /** The number, or the reason, whichever there is. */
        public String describe() {
            return observed ? String.valueOf(value) : "unobservable (" + whyNot + ")";
        }
    }
}
