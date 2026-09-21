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
 * Which way the ambiguous window falls.
 *
 * <p>There is no default and there will not be one. A cutover has a moment where neither cluster
 * can be said to hold the truth, and the only two honest answers are "a message may be processed
 * twice" and "a message may be stranded". The tool makes the operator choose, in the file, days
 * before the cutover runs — the validator refuses a blue/green or a canary without it.
 *
 * <p>A mirror has no semantics, because nothing moves.
 */
public enum Semantics {

    /** A message may be processed on both clusters. Duplicates are designed against. */
    AT_LEAST_ONCE("atLeastOnce"),

    /** A message may be stranded. Nothing is processed twice, and something may not be processed. */
    AT_MOST_ONCE("atMostOnce");

    private final String wire;

    Semantics(String wire) {
        this.wire = wire;
    }

    /** The spelling the deployment file uses. */
    public String wire() {
        return wire;
    }

    /** The semantics written as {@code wire}, or empty if there is no such value. */
    public static Optional<Semantics> of(String wire) {
        return Arrays.stream(values()).filter(candidate -> candidate.wire.equals(wire)).findFirst();
    }

    /** Both spellings, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(Semantics::wire).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
