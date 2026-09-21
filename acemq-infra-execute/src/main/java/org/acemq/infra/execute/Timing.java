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

import java.time.Duration;
import java.time.Instant;

/**
 * The clock and the wait, behind an interface so that a fifteen-minute timeout can be tested in a
 * millisecond.
 *
 * <p>The planner could be a pure function because it never waits. The executor's whole job is
 * waiting, and a test suite that waited for real would be a suite nobody runs — so the one guard
 * behaviour that matters most, the settle window that keeps a stale zero from ending a drain, would
 * be the one behaviour with no test. That is the trade this interface exists to avoid, and it is
 * the same reason {@code Environment} exists in the configuration model.
 */
public interface Timing {

    /** What time it is. */
    Instant now();

    /**
     * Waits.
     *
     * @param duration how long
     */
    void pause(Duration duration);

    /**
     * The real one: the system clock, and a thread that actually sleeps.
     *
     * @return timing against the wall
     */
    static Timing real() {
        return new Timing() {
            @Override
            public Instant now() {
                return Instant.now();
            }

            @Override
            public void pause(Duration duration) {
                if (duration.isNegative() || duration.isZero()) {
                    return;
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException interrupted) {
                    // Someone has asked this process to stop while a cutover is in the middle of
                    // a guard. Re-flagging it and getting out is the only honest response: the
                    // caller's loop sees the flag and aborts, rather than swallowing the request
                    // and carrying on writing to a broker.
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
