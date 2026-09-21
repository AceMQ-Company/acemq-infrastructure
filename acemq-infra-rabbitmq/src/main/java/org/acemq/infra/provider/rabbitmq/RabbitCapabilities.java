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
package org.acemq.infra.provider.rabbitmq;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.rabbitmq.ManagementEndpoint.Presence;

/**
 * What a cluster's version, plugins and permissions add up to.
 *
 * <p>This is the arithmetic docs/broker-agnostic.md describes, written out. The claim on that page
 * is that the capability set earns its place on a single broker because it is not a property of
 * "RabbitMQ" — it is a property of <em>this cluster, at this version, with these plugins enabled,
 * read by this user</em>. Three different reasons for a capability to be missing, and each of them
 * has a different fix:
 *
 * <ul>
 *   <li><strong>A plugin.</strong> {@code rabbitmq_shovel} and its management half are not enabled
 *       by default and plenty of production clusters do not have them. The fix is a
 *       {@code rabbitmq-plugins enable}, which somebody can do in a minute.</li>
 *   <li><strong>A version.</strong> Operator policies arrived in 3.7 and stream queues in 3.9. The
 *       fix is an upgrade, which is not a minute and is very often the thing the cutover exists
 *       to do.</li>
 *   <li><strong>A permission.</strong> A read-only monitoring account cannot close a connection or
 *       import definitions however new the broker is. The fix is a tag on a user, and this is the
 *       one that is invisible until the moment it matters, because everything a probe reads works
 *       perfectly with monitoring alone.</li>
 * </ul>
 *
 * <p>So every verdict carries the observation behind it, and the plan prints the observation
 * rather than the verdict. "blue has rabbitmq_shovel disabled, so drain-messages cannot run" is
 * worth something; "DRAIN_BY_SHOVEL: false" is worth nothing at three in the morning.
 *
 * <p>Two of the nine are conclusions rather than observations, and they are marked as such in the
 * reasons they give. Nothing short of writing can prove that a definitions import or a connection
 * close would be permitted, and a probe that proved it by doing it would not be a probe. Those two
 * are read off the user's tags, which is what the broker will check when the executor asks.
 */
final class RabbitCapabilities {

    private RabbitCapabilities() {
    }

    /**
     * Works out what a cluster can do and writes it onto a probe result.
     *
     * @param facts what the broker said
     * @param builder the probe result being assembled
     * @return the same builder, with the facilities and every capability filled in
     */
    static ProbedCluster.Builder of(BrokerFacts facts, ProbedCluster.Builder builder) {
        builder.facility("shovel", facts.shovel() == Presence.PRESENT)
                .facility("federation", facts.federation() == Presence.PRESENT)
                .facility("streams", facts.streamQueues());

        topology(facts, builder);
        movement(facts, builder);
        attachments(facts, builder);
        policies(facts, builder);
        streams(facts, builder);
        return builder;
    }

    private static void topology(BrokerFacts facts, ProbedCluster.Builder builder) {
        if (facts.definitionsReadable()) {
            builder.can(Capability.TOPOLOGY_EXPORT, "the definitions document was read");
        } else {
            builder.cannot(Capability.TOPOLOGY_EXPORT, "the definitions document could not be"
                    + " read with these credentials (the user has " + facts.describeTags()
                    + "; an export needs the administrator tag)");
        }

        // A conclusion rather than an observation: the only way to prove an import would be
        // permitted is to perform one, and this command's promise is that it does not.
        if (facts.administrator()) {
            builder.can(Capability.TOPOLOGY_IMPORT_MERGE,
                    "the management user has the administrator tag");
        } else {
            builder.cannot(Capability.TOPOLOGY_IMPORT_MERGE, "the management user has "
                    + facts.describeTags() + "; importing definitions needs administrator");
        }
    }

    private static void movement(BrokerFacts facts, ProbedCluster.Builder builder) {
        capability(builder, Capability.DRAIN_BY_SHOVEL, facts, facts.shovel(),
                "rabbitmq_shovel and rabbitmq_shovel_management", "/api/shovels");
        capability(builder, Capability.MIRROR_BY_FEDERATION, facts, facts.federation(),
                "rabbitmq_federation and rabbitmq_federation_management",
                "/api/federation-links");
    }

    /**
     * A capability that needs a plugin's endpoint <em>and</em> the right to declare the runtime
     * parameter that drives it.
     *
     * <p>Both halves, because a shovel is a parameter and a user with the monitoring tag can see
     * the shovel endpoint perfectly well and cannot declare anything on it. That combination — a
     * monitoring account on a cluster where the plugins are all enabled — is the one that reads as
     * healthy right up until the drain.
     */
    private static void capability(ProbedCluster.Builder builder, Capability capability,
                                   BrokerFacts facts, Presence presence, String plugins,
                                   String endpoint) {
        switch (presence) {
            case PRESENT:
                if (facts.canDeclareParameters()) {
                    builder.can(capability, endpoint + " answered and the user may declare"
                            + " parameters");
                } else {
                    builder.cannot(capability, plugins + " are enabled, and the management user"
                            + " has " + facts.describeTags() + "; declaring one needs"
                            + " administrator or policymaker");
                }
                return;
            case ABSENT:
                builder.cannot(capability, plugins + " " + (plugins.contains(" and ") ? "are" : "is")
                        + " not enabled on this cluster (" + endpoint + " is not there)");
                return;
            case FORBIDDEN:
                builder.cannot(capability, "these credentials may not read " + endpoint
                        + ", so whether " + plugins + " are enabled could not be established");
                return;
            default:
                builder.cannot(capability, endpoint + " did not answer");
        }
    }

    private static void attachments(BrokerFacts facts, ProbedCluster.Builder builder) {
        if (facts.consumersReadable() && facts.canMonitor()) {
            builder.can(Capability.CONSUMER_INSPECT, "the consumer list was read");
        } else if (!facts.canMonitor()) {
            builder.cannot(Capability.CONSUMER_INSPECT, "the management user has "
                    + facts.describeTags() + "; seeing other users' consumers needs monitoring"
                    + " or administrator");
        } else {
            builder.cannot(Capability.CONSUMER_INSPECT, "the consumer list could not be read");
        }

        // Closing somebody else's connection is an administrator's right. A read-only monitoring
        // account can watch the connection it is about to fail to close.
        if (facts.administrator()) {
            builder.can(Capability.CONNECTION_CLOSE,
                    "the management user has the administrator tag");
        } else {
            builder.cannot(Capability.CONNECTION_CLOSE, "the management user has "
                    + facts.describeTags() + "; closing another user's connection needs"
                    + " administrator");
        }

        // The arguments a queue was declared with come back on the queue listing itself, which is
        // the point of reading them here rather than over AMQP, where they do not come back at all.
        builder.can(Capability.QUEUE_ARGUMENTS, "the queue listing carries its arguments");
    }

    private static void policies(BrokerFacts facts, ProbedCluster.Builder builder) {
        if (!facts.atLeast(3, 7)) {
            builder.cannot(Capability.OPERATOR_POLICY, "RabbitMQ " + facts.version()
                    + " has no operator policies; they arrived in 3.7");
        } else if (facts.administrator()) {
            builder.can(Capability.OPERATOR_POLICY, "RabbitMQ " + facts.version()
                    + " and the administrator tag");
        } else {
            builder.cannot(Capability.OPERATOR_POLICY, "the management user has "
                    + facts.describeTags() + "; an operator policy needs administrator");
        }
    }

    private static void streams(BrokerFacts facts, ProbedCluster.Builder builder) {
        if (!facts.atLeast(3, 9)) {
            builder.cannot(Capability.STREAM_OFFSET_READ, "RabbitMQ " + facts.version()
                    + " has no streams; they arrived in 3.9");
        } else if (!facts.streamQueues()) {
            builder.cannot(Capability.STREAM_OFFSET_READ,
                    "the stream_queue feature flag is not enabled");
        } else {
            // A read, never a write. docs/message-state.md: there is no API at any version for
            // writing a consumer's offset into another cluster's stream, and if there were it
            // would be meaningless, because green's log is a different log with different numbers.
            builder.can(Capability.STREAM_OFFSET_READ, "stream queues are enabled — a read only,"
                    + " because an offset cannot be written into another cluster's log");
        }
    }
}
