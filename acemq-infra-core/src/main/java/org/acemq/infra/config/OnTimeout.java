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

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What happens when a guard's timeout expires.
 *
 * <p>The point of putting this in the file is that the decision gets made at review time, by
 * people with the whole picture, rather than at 3am by whoever is watching the timer. That is why
 * every waiting step carries its own policy instead of the format having one global timeout.
 */
public enum OnTimeout {

    /**
     * Stop. The default anywhere downstream of something destructive, because continuing past a
     * drain that has not finished is how messages end up on neither cluster.
     */
    ABORT("abort"),

    /** Carry on regardless. Reasonable for an observation; rarely reasonable for a cutover. */
    CONTINUE("continue"),

    /** Stop and ask a human, who is assumed to be watching. */
    PROMPT("prompt");

    private final String wire;

    OnTimeout(String wire) {
        this.wire = wire;
    }

    /** The spelling the deployment file uses. */
    public String wire() {
        return wire;
    }

    /** The policy written as {@code wire}, or empty if there is no such policy. */
    public static Optional<OnTimeout> of(String wire) {
        return Arrays.stream(values()).filter(candidate -> candidate.wire.equals(wire)).findFirst();
    }

    /** Every spelling, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(OnTimeout::wire).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
