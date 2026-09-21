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

import java.util.List;

import org.acemq.infra.provider.rabbitmq.ManagementEndpoint.Presence;

/**
 * What the broker said, before anything has been concluded from it.
 *
 * <p>The probe is two jobs and this record is the seam between them. One job asks a live cluster a
 * dozen questions, which needs a broker. The other decides what the answers add up to — that a
 * 3.6 cluster cannot have {@code OPERATOR_POLICY} however willing its credentials are, that a
 * shovel endpoint answering 403 is a permissions problem and not a missing plugin — and that job
 * is arithmetic over eight fields.
 *
 * <p>Splitting them means the second one is tested without a container, exhaustively, including
 * the combinations that are a nuisance to arrange for real: a monitoring-only user on a modern
 * broker, an administrator on a broker too old for half of what it is being asked for, a cluster
 * with the federation plugin and not the shovel. Those are exactly the estates where a cutover
 * tool has to stop, and a suite that could only reach them by configuring containers would reach
 * two of them and skip the rest.
 *
 * @param version the version string the broker reported, or {@code unknown}
 * @param tags the management user's tags, as the broker reports them
 * @param shovel what {@code /api/shovels} said
 * @param federation what {@code /api/federation-links} said
 * @param streamQueues whether the broker's {@code stream_queue} feature flag is enabled
 * @param definitionsReadable whether the definitions document could actually be exported
 * @param consumersReadable whether the consumer list could actually be read
 */
record BrokerFacts(String version, List<String> tags, Presence shovel, Presence federation,
                   boolean streamQueues, boolean definitionsReadable, boolean consumersReadable) {

    /** The tag that permits everything, including the two things a cutover needs to write. */
    private static final String ADMINISTRATOR = "administrator";

    /** The tag that permits seeing other people's connections and consumers. */
    private static final String MONITORING = "monitoring";

    /** The tag that permits declaring a runtime parameter, which is what a shovel is. */
    private static final String POLICYMAKER = "policymaker";

    BrokerFacts {
        tags = List.copyOf(tags);
    }

    /** Whether the management user may write definitions, policies and connections. */
    boolean administrator() {
        return tags.contains(ADMINISTRATOR);
    }

    /** Whether it may see what other users are doing. */
    boolean canMonitor() {
        return tags.contains(ADMINISTRATOR) || tags.contains(MONITORING);
    }

    /** Whether it may declare the runtime parameter a shovel or a federation upstream is. */
    boolean canDeclareParameters() {
        return tags.contains(ADMINISTRATOR) || tags.contains(POLICYMAKER);
    }

    /** The tags as an error message should list them, or a phrase saying there were none. */
    String describeTags() {
        return tags.isEmpty() ? "no tags at all" : String.join(", ", tags);
    }

    /**
     * Whether the broker is at least this version.
     *
     * <p>Compares the leading numbers and stops at the first thing that is not one, because a
     * broker reports {@code 3.13.7} and also {@code 4.1.0-rc.1} and {@code 3.12.14+dfsg1}. A
     * version this cannot read at all is treated as too old, which is the direction that refuses
     * a plan rather than the direction that promises a capability nobody established.
     *
     * @param major the major version to reach
     * @param minor the minor version to reach
     * @return whether the broker is at or past it
     */
    boolean atLeast(int major, int minor) {
        String[] parts = version.split("[^0-9]+");
        int found = 0;
        int[] numbers = new int[2];
        for (String part : parts) {
            if (part.isEmpty() || found == 2) {
                continue;
            }
            numbers[found++] = Integer.parseInt(part);
        }
        if (found == 0) {
            return false;
        }
        return numbers[0] > major || (numbers[0] == major && numbers[1] >= minor);
    }
}
