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

import java.util.Map;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFileParser;
import org.acemq.infra.config.Environment;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;

/**
 * The files the executor is tested against, parsed by the real parser.
 *
 * <p>Written out as YAML rather than assembled as records, and that is worth a sentence. The point
 * of the configuration format is that the file is the interface — docs/library.md says so — and a
 * suite that built {@code Step} and {@code Action} values directly would be testing the executor
 * against a model somebody typed rather than against a document somebody could write. The parser
 * and the validator are between the two in production and they are between the two here.
 */
final class Deployments {

    /**
     * The worked blue/green from examples/blue-green.yaml, with the variables already substituted
     * and the announcement pointing somewhere.
     */
    static final String BLUE_GREEN = """
            apiVersion: acemq.org/v1alpha1
            kind: Deployment
            metadata:
              name: orders-blue-green

            clusters:
              blue:
                management: https://blue.internal:15671
                amqp: amqps://blue.internal:5671
                vhost: /orders
                username: admin
                password: secret
              green:
                management: https://green.internal:15671
                amqp: amqps://green.internal:5671
                vhost: /orders
                username: admin
                password: secret

            provider: rabbitmq

            endpoint:
              kind: external
              description: orders-amqp.internal is a CNAME switched by the platform team.

            deployment:
              operation: blueGreen
              from: blue
              to: green
              semantics: atLeastOnce

              backup:
                enabled: false

              announce:
                exchange: orders.events
                routingKey: deployment.started
                payload: '{"status":"started"}'

              steps:
                - id: probe
                  requires:
                    - TOPOLOGY_EXPORT
                    - TOPOLOGY_IMPORT_MERGE
                    - DRAIN_BY_SHOVEL
                    - CONNECTION_CLOSE
                    - CONSUMER_INSPECT

                - id: topology
                  copyTopology:
                    from: blue
                    to: green
                    vhosts: ["/orders"]
                    include: [exchanges, queues, bindings, users, permissions, parameters]
                    exclude: [policies, operatorPolicies]

                - id: announce-drain
                  announce: {}

                - id: pause-producers
                  waitFor:
                    on: blue
                    publishRate: 0
                    timeout: 2m
                    onTimeout: prompt

                - id: drain-consumers
                  closeConnections:
                    on: blue
                    select:
                      users: [orders-service]
                      role: consumer
                    after:
                      unacked: 0
                      timeout: 5m
                      onTimeout: abort
                  waitFor:
                    on: blue
                    unacked: 0
                    timeout: 5m
                    onTimeout: abort

                - id: drain-messages
                  drain:
                    from: blue
                    to: green
                    queues: ["orders.*", "!orders.audit"]
                    ackMode: onConfirm
                    deleteAfter: queueLength
                  waitFor:
                    on: blue
                    depth: 0
                    timeout: 15m
                    onTimeout: abort

                - id: policies
                  copyTopology:
                    from: blue
                    to: green
                    vhosts: ["/orders"]
                    include: [policies, operatorPolicies]

                - id: switch-endpoint
                  endpoint:
                    target: green

                - id: verify
                  waitFor:
                    on: green
                    consumers:
                      min: 1
                    timeout: 5m
                    onTimeout: abort
            """;

    /**
     * A canary of one workload out of the same estate, with no {@code steps:} of its own.
     *
     * <p>Deliberately without them: a canary's default list is where {@code scope.queues} becomes
     * the drain's patterns and {@code scope.services} becomes the close step's selector, and a file
     * that wrote its own steps would be testing the file rather than that translation.
     */
    static final String CANARY = """
            apiVersion: acemq.org/v1alpha1
            kind: Deployment
            metadata:
              name: notifications-canary

            clusters:
              blue:
                management: https://blue.internal:15671
                amqp: amqps://blue.internal:5671
                vhost: /orders
                username: admin
                password: secret
              green:
                management: https://green.internal:15671
                amqp: amqps://green.internal:5671
                vhost: /orders
                username: admin
                password: secret

            provider: rabbitmq

            endpoint:
              # External rather than the hook examples/canary.yaml writes, because a hook here
              # would be a test of whether a shell script exists. What a canary needs of the
              # endpoint -- that it move one service and leave everything else alone -- is not
              # something either kind can be checked for, which is why the run says so in a note.
              kind: external
              description: notification-service resolves through a per-service mesh route.

            deployment:
              operation: canary
              from: blue
              to: green
              semantics: atLeastOnce

              backup:
                enabled: false

              scope:
                vhost: /orders
                queues: [orders.notifications]
                services: [notification-service]
            """;

    /** A mirror of one exchange, which moves nothing and ends when somebody stops it. */
    static final String MIRROR = """
            apiVersion: acemq.org/v1alpha1
            kind: Deployment
            metadata:
              name: orders-shadow-mirror

            clusters:
              blue:
                management: https://blue.internal:15671
                amqp: amqps://blue.internal:5671
                vhost: /orders
                username: admin
                password: secret
              green:
                management: https://green.internal:15671
                amqp: amqps://green.internal:5671
                vhost: /orders
                username: admin
                password: secret

            provider: rabbitmq

            endpoint:
              kind: external
              description: No endpoint change. Blue remains authoritative throughout.

            deployment:
              operation: mirror
              from: blue
              to: green

              mirror:
                exchanges: ["orders"]
                upstream:
                  uri: amqps://blue.internal:5671
                  prefetch: 1000
                  ackMode: onConfirm
            """;

    private Deployments() {
    }

    /**
     * Parses one of these.
     *
     * @param yaml the document
     * @return the parsed file
     */
    static DeploymentFile file(String yaml) {
        return DeploymentFileParser.parse(yaml, "test.yaml", Environment.of(Map.of()));
    }

    /** The blue/green worked example. */
    static DeploymentFile blueGreen() {
        return file(BLUE_GREEN);
    }

    /** The canary, which moves {@code orders.notifications} and leaves the rest of the estate. */
    static DeploymentFile canary() {
        return file(CANARY);
    }

    /** The mirror, which moves nothing. */
    static DeploymentFile mirror() {
        return file(MIRROR);
    }

    /**
     * The same four queues, with a consumer listing that says who is on which.
     *
     * <p>{@code notification-service} is on the queue the canary moves and on nothing else, and
     * {@code orders-service} is on the two that stay. That is the estate a canary is allowed to
     * run against — the scope is closed — and it is the baseline the refusal fixtures depart from
     * one edge at a time.
     *
     * @param name the deployment file's name for the cluster
     * @return the probe result
     */
    static ProbedCluster closedScope(String name) {
        return scoped(name, Inventory.counting()
                .queue("orders.new", "classic", 27412, 8)
                .queue("orders.notifications", "classic", 40, 2)
                .queue("orders.audit", "classic", 190, 1)
                .queue("orders.priority", "quorum", 4, 1)
                .connection("10.0.0.1:52000", "orders-service", 4)
                .connection("10.0.0.3:52002", "notification-service", 2)
                .consumer("orders.notifications", "10.0.0.3:52002", "notification-service")
                .consumer("orders.new", "10.0.0.1:52000", "orders-service")
                .consumer("orders.audit", "10.0.0.1:52000", "orders-service")
                .build());
    }

    /**
     * A cluster that can do everything, holding whatever the test needs it to hold.
     *
     * @param name the deployment file's name for it
     * @param inventory what is on it
     * @return the probe result
     */
    static ProbedCluster scoped(String name, Inventory inventory) {
        return ProbedCluster.named(name)
                .version("4.0.5")
                .can(Capability.values())
                .inventory(inventory)
                .build();
    }

    /**
     * A cluster that can do everything a cutover asks of it, with four queues on it.
     *
     * @param name the deployment file's name for it
     * @return the probe result
     */
    static ProbedCluster capable(String name) {
        return ProbedCluster.named(name)
                .version("4.0.5")
                .can(Capability.TOPOLOGY_EXPORT, Capability.TOPOLOGY_IMPORT_MERGE,
                        Capability.DRAIN_BY_SHOVEL, Capability.MIRROR_BY_FEDERATION,
                        Capability.CONNECTION_CLOSE, Capability.CONSUMER_INSPECT,
                        Capability.OPERATOR_POLICY, Capability.QUEUE_ARGUMENTS)
                .inventory(Inventory.counting()
                        .queue("orders.new", "classic", 27412, 8)
                        .queue("orders.notifications", "classic", 0, 2)
                        .queue("orders.audit", "classic", 190, 1)
                        .queue("orders.priority", "quorum", 4, 1)
                        .connection("10.0.0.1:52000", "orders-service", 4)
                        .connection("10.0.0.2:52001", "orders-service", 0)
                        .build())
                .build();
    }

    /**
     * One side of a run.
     *
     * @param name the cluster's name
     * @param broker the broker behind it
     * @return the side
     */
    static Run.Side side(String name, Broker broker) {
        return new Run.Side(name, capable(name), broker, "amqp://" + name + ":5672");
    }
}
