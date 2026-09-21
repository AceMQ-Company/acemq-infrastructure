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
 * What this deployment is: {@code blueGreen}, {@code canary} or {@code mirror}.
 *
 * <p>One field with three values, replacing the {@code type} + {@code strategy} + {@code mode}
 * triple the old format carried for one idea — two of which could not vary and one of which,
 * {@code RollingUpdate}, described nothing a broker does. docs/configuration.md has the argument.
 *
 * <p>These are genuinely different operations rather than settings on one. A mirror copies and
 * never moves, so it has no {@code semantics} and no {@code rollback}; a canary moves one whole
 * workload and has a scope; a blue/green moves everything.
 */
public enum Operation {

    /** Everything moves from one cluster to the other. */
    BLUE_GREEN("blueGreen"),

    /** One workload moves, completely. Not a traffic split — docs/canary.md. */
    CANARY("canary"),

    /** Everything published to the source is copied to the target. Nothing moves. */
    MIRROR("mirror");

    private final String wire;

    Operation(String wire) {
        this.wire = wire;
    }

    /** The spelling the deployment file uses. */
    public String wire() {
        return wire;
    }

    /** The operation written as {@code wire}, or empty if there is no such operation. */
    public static Optional<Operation> of(String wire) {
        return Arrays.stream(values()).filter(candidate -> candidate.wire.equals(wire)).findFirst();
    }

    /** Every spelling, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(Operation::wire).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
