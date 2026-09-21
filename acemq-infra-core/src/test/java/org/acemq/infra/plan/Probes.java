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
package org.acemq.infra.plan;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;

/**
 * Two clusters that never existed.
 *
 * <p>This class is the argument for the planner being a pure function, stated as code. Everything
 * the planner needs to know about a broker arrives as a value, so a test can describe an estate —
 * a 3.12 cluster with the shovel plugin missing, a source holding twenty-seven thousand messages
 * and two streams — in six lines, and get the plan that estate would produce without a container
 * anywhere. Every one of those situations is one that would take a morning to arrange for real,
 * and half of them would be arranged wrongly.
 */
final class Probes {

    private Probes() {
    }

    /**
     * A healthy source: everything enabled, a topology of a believable size, a backlog, and two
     * streams sitting in the middle of the drain's scope where they cause the most trouble.
     */
    static ProbedCluster.Builder blue() {
        Inventory.Builder inventory = Inventory.counting()
                .exchanges(14).bindings(58).users(6).permissions(12)
                .policies(4).operatorPolicies(1).parameters(2);
        // Eight connections belonging to orders-service and consuming, one belonging to it and
        // only publishing, and one belonging to somebody else. The selector in the close step has
        // to cut all three ways and a fixture where every connection is identical cannot see it.
        for (int index = 0; index < 8; index++) {
            inventory.connection("192.168.1.1" + index + ":5100" + index + " -> 10.0.0.2:5672",
                    "orders-service", 2);
        }
        inventory.connection("192.168.1.30:51030 -> 10.0.0.2:5672", "orders-service", 0);
        inventory.connection("192.168.1.31:51031 -> 10.0.0.2:5672", "reporting", 1);
        // Twenty-eight ordinary queues, the audit queue the file deliberately leaves behind, and
        // two streams. Thirty-one in total, thirty of them inside `orders.*`.
        long messages = 27_412;
        for (int index = 1; index <= 28; index++) {
            long held = index == 1 ? messages - 27 * 13 : 13;
            inventory.queue("orders." + index, index == 4 ? "quorum" : "classic", held, 2);
        }
        inventory.queue("orders.audit", "classic", 91, 1);
        inventory.queue("orders.events", "stream", 0, 3);
        inventory.queue("orders.events.raw", "stream", 0, 1);

        return ProbedCluster.named("blue").version("3.13.7")
                .facility("shovel", true).facility("federation", true).facility("streams", true)
                .inventory(inventory.build())
                .can(Capability.values());
    }

    /** A healthy, empty target. */
    static ProbedCluster.Builder green() {
        return ProbedCluster.named("green").version("4.0.5")
                .facility("shovel", true).facility("federation", true).facility("streams", true)
                .inventory(Inventory.counting().build())
                .can(Capability.values());
    }
}
