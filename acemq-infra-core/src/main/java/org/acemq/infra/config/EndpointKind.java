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
 * How clients find out which cluster is live.
 *
 * <p>Two values, and the deliberate absence of a third. The old format had
 * {@code shovelFixedDestination: amqp://admin:admin@green-haproxy:5672}, which assumed a proxy in
 * front of green was the tool's business and gave the assumption no name. DNS, load balancers,
 * service meshes and connection strings belong to the estate; the tool either runs a hook the
 * estate wrote or stops and tells a human what to switch. There is no mode where it guesses.
 */
public enum EndpointKind {

    /** The tool stops, prints the description, and waits for a human. */
    EXTERNAL("external"),

    /** The tool runs a command the estate supplied, and that command owns the switch. */
    HOOK("hook");

    private final String wire;

    EndpointKind(String wire) {
        this.wire = wire;
    }

    /** The spelling the deployment file uses. */
    public String wire() {
        return wire;
    }

    /** The kind written as {@code wire}, or empty if there is no such kind. */
    public static Optional<EndpointKind> of(String wire) {
        return Arrays.stream(values()).filter(candidate -> candidate.wire.equals(wire)).findFirst();
    }

    /** Both spellings, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(EndpointKind::wire).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
