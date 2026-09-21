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
package org.acemq.infra.provider;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a cluster can actually do, as set out in docs/broker-agnostic.md.
 *
 * <p>The vocabulary is shared by two parties that never meet. A deployment file names capabilities
 * in a {@code probe} step's {@code requires:} list and the validator checks the names without any
 * broker in sight; a provider's {@code probe()} returns the set a live cluster turned out to have.
 * One enum, so that a capability cannot be spelled one way in a file and another way in a probe
 * result — which is precisely how a capability model becomes decoration.
 *
 * <p>It lives in {@code org.acemq.infra.provider} rather than beside the configuration model for
 * the same reason it is drawn that way in docs/broker-agnostic.md: the seam is the verbs
 * <em>and</em> the capability set together, and the verbs land in this package next. The
 * dependency runs one way — the validator reads this enum, and nothing here knows a deployment
 * file exists.
 *
 * <p>The thing that keeps this honest on a single broker is that the set is not a property of
 * "RabbitMQ". It is a property of this cluster, at this version, with these plugins enabled and
 * this management user's permissions, which is why {@code probe()} is a live call and why
 * {@code plan} makes it first.
 */
public enum Capability {

    /** Read a cluster's configuration. RabbitMQ: the definitions document. */
    TOPOLOGY_EXPORT("read a cluster's configuration"),

    /** Write that configuration onto another cluster without deleting what is already there. */
    TOPOLOGY_IMPORT_MERGE("write it onto another without deleting what is there"),

    /**
     * Move messages. Source-destructive: they leave.
     *
     * <p>Needs {@code rabbitmq_shovel} and {@code rabbitmq_shovel_management}, which plenty of
     * production clusters do not have enabled.
     */
    DRAIN_BY_SHOVEL("move messages, source-destructive"),

    /**
     * Copy messages. Source-preserving, and specifically <em>exchange</em> federation.
     *
     * <p>The plugin also offers queue federation, which pulls only when the upstream has no local
     * consumers — a conditional move rather than a copy, and not what this verb means. The
     * validator refuses a mirror that asks for it; docs/message-state.md has the table.
     */
    MIRROR_BY_FEDERATION("copy messages, source-preserving (exchange federation)"),

    /**
     * Force a client to reconnect.
     *
     * <p>Needs a management user with a role that permits it, which a read-only monitoring account
     * does not have — so this one can be absent for a reason that has nothing to do with the
     * broker's version or its plugins.
     */
    CONNECTION_CLOSE("force a client to reconnect"),

    /** See who is consuming what. */
    CONSUMER_INSPECT("see who is consuming what"),

    /** Apply a policy an application cannot override. Needs RabbitMQ 3.7 or later. */
    OPERATOR_POLICY("apply a policy an application cannot override"),

    /**
     * Read a stream consumer's position. Needs 3.9 or later and the stream plugin.
     *
     * <p>A read, never a write. Offsets do not travel between clusters and this tool will not
     * pretend otherwise — docs/message-state.md.
     */
    STREAM_OFFSET_READ("read a stream consumer's position"),

    /** Read the arguments a queue was declared with, which AMQP will not report. */
    QUEUE_ARGUMENTS("read the arguments a queue was declared with");

    private final String description;

    Capability(String description) {
        this.description = description;
    }

    /** One line, in the words docs/broker-agnostic.md uses, for the plan output. */
    public String description() {
        return description;
    }

    /**
     * The capability named {@code name}, or empty if there is no such capability.
     *
     * <p>Exact match. A file that writes {@code drain_by_shovel} is refused rather than guessed
     * at: a capability list is an assertion about what must be true before anything happens, and
     * a tool that silently accepted a near-miss would assert something the file did not say.
     */
    public static Optional<Capability> of(String name) {
        return Arrays.stream(values()).filter(candidate -> candidate.name().equals(name)).findFirst();
    }

    /** Every capability name, sorted, for the "expected one of" half of an error message. */
    public static String names() {
        return Arrays.stream(values()).map(Enum::name).sorted().collect(Collectors.joining(", "));
    }
}
