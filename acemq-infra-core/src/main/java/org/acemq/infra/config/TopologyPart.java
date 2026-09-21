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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pieces of a cluster's shape that a {@code copyTopology} step can include or exclude.
 *
 * <p>{@link #RETENTION} is the part of this enum that does real work. A definitions import applies
 * its policies the instant it lands, and a {@code message-ttl} or {@code max-length} policy that
 * is live on the target before the backlog arrives discards the backlog on arrival. That is why
 * the topology copy in every worked example is split in two with the drain between the halves,
 * and why the validator refuses the arrangement where it is not. docs/message-state.md.
 */
public enum TopologyPart {

    EXCHANGES("exchanges"),
    QUEUES("queues"),
    BINDINGS("bindings"),
    USERS("users"),
    PERMISSIONS("permissions"),
    PARAMETERS("parameters"),
    POLICIES("policies"),
    OPERATOR_POLICIES("operatorPolicies"),
    VHOSTS("vhosts");

    /**
     * The parts that take effect the moment they land and can therefore destroy a backlog that has
     * not arrived yet.
     */
    public static final Set<TopologyPart> RETENTION = Set.of(POLICIES, OPERATOR_POLICIES);

    private final String wire;

    TopologyPart(String wire) {
        this.wire = wire;
    }

    /** The spelling the deployment file uses. */
    public String wire() {
        return wire;
    }

    /** The part written as {@code wire}, or empty if there is no such part. */
    public static Optional<TopologyPart> of(String wire) {
        return Arrays.stream(values()).filter(candidate -> candidate.wire.equals(wire)).findFirst();
    }

    /** Every spelling, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(TopologyPart::wire).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
