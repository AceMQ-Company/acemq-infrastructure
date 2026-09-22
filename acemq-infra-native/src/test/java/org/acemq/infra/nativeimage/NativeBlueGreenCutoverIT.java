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
package org.acemq.infra.nativeimage;

import static org.acemq.infra.nativeimage.Lab.admin;
import static org.acemq.infra.nativeimage.Lab.amqp;
import static org.acemq.infra.nativeimage.Lab.depth;
import static org.acemq.infra.nativeimage.Lab.eventually;
import static org.acemq.infra.nativeimage.Lab.management;
import static org.acemq.infra.nativeimage.Lab.names;
import static org.acemq.infra.nativeimage.Lab.policyNames;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The cutover from {@code BlueGreenCutoverIT}, run by the binary instead of by this JVM.
 *
 * <p>This is the test phase four exists for. docs/roadmap.md and docs/shape.md both state the rule
 * in the same words — <strong>the test suite runs against the binary, not the jar</strong> — and the
 * reason is specific rather than general: reflection is what a native image takes away, a missing
 * registration does not fail the build, and the place it surfaces is a management API response that
 * cannot be turned back into an object. Every command here goes through the process boundary, and
 * the first thing it did when it was written was fail: the image built clean, {@code plan} reported
 * both clusters as "usually something other than RabbitMQ answering on that port", and what was
 * actually wrong was that Jackson had been handed a class it was not allowed to construct. A jar
 * build had been green through all of it.
 *
 * <h2>What is the same and what is different</h2>
 *
 * <p>The estate is the one {@code BlueGreenCutoverIT} builds, down to the message counts, and the
 * deployment file is the same file. What differs is who carries it out and how the questions get
 * answered. Two of the assertions there cannot be made here and it is worth being exact about which
 * rather than leaving the claim vague:
 *
 * <ul>
 *   <li><strong>The rollback.</strong> {@code Execution.rollback()} derives the undo from the steps
 *       that actually reached done, and it is a library method with no command over it —
 *       {@code acemq-infra} has {@code validate}, {@code plan} and {@code apply} and nothing else.
 *       There is no way to ask a binary for it, so the rollback and its duplicate count stay where
 *       they are tested, in {@code BlueGreenCutoverIT} on the JVM. Adding a {@code rollback}
 *       command to make this test reach it would be phase four changing what the tool does, which
 *       is exactly what phase four is not.</li>
 *   <li><strong>The step objects.</strong> What comes back through a process boundary is the
 *       rendered report, so the steps are asserted as the lines an operator reads rather than as a
 *       list of {@code Taken}. That is a fair trade here: the rendering is what a pull request
 *       gets, and the estate itself is read back through a management client of this test's own
 *       either way.</li>
 * </ul>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a cutover, carried out by the binary")
class NativeBlueGreenCutoverIT {

    /** The one thing the two clusters share, and the only reason a shovel can cross. */
    private static final Network LAB = Network.newNetwork();

    @Container
    private static final RabbitMQContainer BLUE = Lab.broker(LAB, "blue");

    @Container
    private static final RabbitMQContainer GREEN = Lab.broker(LAB, "green");

    /** How many messages the workload left on {@code orders.new}. */
    private static final int ORDERS = 120;

    /** And on {@code orders.priority}, so that no drained queue starts empty. */
    private static final int PRIORITY = 20;

    /** Where the backup lands, and where the deployment file is written. */
    @TempDir
    private static Path work;

    private static Path deployment;

    private static Connection blueConsumer;
    private static Connection greenConsumer;

    /** What the application on green was handed, so that the verify step has something to find. */
    private static final ConcurrentLinkedQueue<String> PROCESSED_ON_GREEN =
            new ConcurrentLinkedQueue<>();

    // ---------------------------------------------------------------- the estate

    @BeforeAll
    static void seed() {
        try (RabbitAdmin admin = admin(BLUE)) {
            admin.declareExchange("orders", "topic", true, Map.of());
            admin.declareExchange("deploy.events", "fanout", true, Map.of());
            admin.declareQueue("orders.new", true, Map.of());
            admin.declareQueue("orders.audit", true, Map.of());
            admin.declareQueue("orders.priority", true, Map.of());
            // Outside the drain's patterns on purpose, and the queue the workload on blue sits on:
            // a consumer has to be somewhere for the close step to close it, and putting it on a
            // queue holding the backlog would mean this test's own consumer ate what the drain was
            // supposed to move.
            admin.declareQueue("svc.heartbeat", true, Map.of());
            admin.declareQueue("deploy.log", true, Map.of());
            admin.bindQueue("orders", "orders.new", "order.created", Map.of());
            admin.bindQueue("orders", "orders.priority", "order.priority", Map.of());
            admin.bindQueue("orders", "orders.audit", "#", Map.of());
            admin.bindQueue("deploy.events", "deploy.log", "", Map.of());
            admin.putPolicy("capped", "^orders\\.", Map.of("max-length", 1_000_000), 1);
        }

        try (Connection publishing = Lab.connect(BLUE, BLUE.getAdminUsername(),
                        BLUE.getAdminPassword(), "seed");
                Channel channel = publishing.createChannel()) {
            Lab.publish(channel, "orders", "order.created", "order-", ORDERS);
            Lab.publish(channel, "orders", "order.priority", "priority-", PRIORITY);
        } catch (Exception broken) {
            throw new IllegalStateException("could not seed blue", broken);
        }

        blueConsumer = Lab.connect(BLUE, BLUE.getAdminUsername(), BLUE.getAdminPassword(),
                "orders-service");
        try {
            Channel heartbeat = blueConsumer.createChannel();
            heartbeat.basicConsume("svc.heartbeat", true, new DefaultConsumer(heartbeat));
        } catch (IOException broken) {
            throw new IllegalStateException("the workload on blue could not attach", broken);
        }

        deployment = Lab.file(work, "lab-orders.yaml", yaml());

        eventually("blue reports a consuming connection", () -> {
            try (RabbitAdmin admin = admin(BLUE)) {
                return admin.channels().stream()
                        .anyMatch(channel -> channel.consumerCount() > 0);
            }
        });
        eventually("blue reports the backlog it was given", () -> depth(BLUE, "orders.new") == ORDERS
                && depth(BLUE, "orders.priority") == PRIORITY);
    }

    @AfterAll
    static void disconnect() throws Exception {
        for (Connection connection : new Connection[] {blueConsumer, greenConsumer}) {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        }
    }

    // ---------------------------------------------------------------- before anything is written

    /**
     * The shape a pipeline uses, and the one that has to work without a person.
     *
     * <p>A rehearsal re-probes both clusters and walks every step against them with both brokers
     * wrapped so that a writing verb throws. It is also the command that reaches the most code for
     * the least risk, which makes it the right first thing to point a fresh binary at: a
     * registration missing anywhere in the probe or the executor's reads fails here, before the
     * suite has put a single message anywhere it cannot get it back from.
     */
    @Test
    @Order(1)
    @Timeout(600)
    @DisplayName("rehearses the whole cutover with nobody watching, and writes nothing")
    void theDryRun() {
        Binary.Result rehearsal = Binary.run("apply", "-f", deployment.toString(), "--dry-run");

        assertThat(rehearsal.status()).isZero();
        assertThat(rehearsal.all())
                .contains("backup", "topology", "drain-consumers", "drain-messages",
                        "switch-endpoint", "verify");

        // Nothing was written, which is the half of a rehearsal worth asserting. Green has not even
        // been given the shape.
        assertThat(names(GREEN)).doesNotContain("orders.new", "orders.priority");
        assertThat(depth(BLUE, "orders.new")).isEqualTo(ORDERS);
    }

    /**
     * What {@code --yes} does not buy, checked against the binary rather than against a comment.
     *
     * <p>{@code --yes} consents to the run starting and to nothing after it. This file switches an
     * endpoint the tool does not own, so a run with nobody to tell is refused in preflight — before
     * the first write, rather than nine steps in with the messages already moved. The binary is the
     * only place this can be tested honestly: whether anybody is there is a fact about the process,
     * and in a JVM test it is whatever the test constructed.
     */
    @Test
    @Order(2)
    @Timeout(300)
    @DisplayName("refuses an external endpoint switch to a pipeline, before it writes anything")
    void aPipelineIsRefused() {
        Binary.Result refused = Binary.run("apply", "-f", deployment.toString(), "--yes");

        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.all()).contains("endpoint");
        assertThat(names(GREEN)).doesNotContain("orders.new");
        assertThat(depth(BLUE, "orders.new")).isEqualTo(ORDERS);
    }

    // ---------------------------------------------------------------- the cutover

    /**
     * The cutover, typed at a terminal by somebody who means it.
     *
     * <p>Three questions get asked and all three are answered the way the person they were written
     * for would answer them. They are worth naming because they are three different decisions and
     * not one repeated: the CLI's gate in front of the first write; the executor's own preflight,
     * which asks whether anybody is watching <em>because</em> this file has a step that will stop,
     * and asks it before the drain rather than at the drain; and the switch itself. The last one is
     * also the moment the applications move, which is what an {@code endpoint: external} switch
     * <em>is</em>: the tool stops, somebody changes a CNAME, the clients arrive on the other
     * cluster, and the step after it exists to notice when they did not.
     */
    @Test
    @Order(3)
    @Timeout(1200)
    @DisplayName("moves the estate to green when a person answers, and says so step by step")
    void theCutover() {
        Binary.Result done;
        try (Binary.Session session = Binary.atATerminal("apply", "-f", deployment.toString())) {
            String gate = session.awaitQuestion();
            assertThat(gate).contains("about to run lab-orders");
            session.answer("yes");

            String watching = session.awaitQuestion();
            assertThat(watching).contains("is anyone watching this run");
            session.answer("yes");

            String endpoint = session.awaitQuestion();
            assertThat(endpoint).contains("Switch the endpoint to green");
            applicationArrivesOnGreen();
            session.answer("yes");

            done = session.awaitExit(Duration.ofMinutes(15));
        }

        assertThat(done.status()).isZero();
        // Every step, in the order docs/blue-green.md numbers them.
        assertThat(done.all()).contains("backup", "topology", "announce-drain", "pause-producers",
                "drain-consumers", "drain-messages", "policies", "switch-endpoint", "verify");
        // The workload on blue was found and closed, which is the verb the whole ordering is built
        // around: an unsettled delivery is requeued on the cluster being left, not on the one the
        // messages are going to.
        assertThat(done.flat()).contains("closed 1");
        // Published to an exchange something is bound to, which is the half of an announcement
        // worth having: one routed nowhere is accepted by the broker and heard by nobody.
        assertThat(done.flat()).contains("routed to at least");
    }

    @Test
    @Order(4)
    @Timeout(120)
    @DisplayName("leaves blue empty of everything the drain selected, and green holding it")
    void theMessagesMoved() {
        assertThat(depth(BLUE, "orders.new")).isZero();
        assertThat(depth(BLUE, "orders.priority")).isZero();
        assertThat(depth(GREEN, "orders.priority")).isEqualTo(PRIORITY);

        // All of it, and none of it lost to the application that arrived at the endpoint switch.
        // The management API's `messages` is ready plus unacknowledged rather than ready alone, so
        // the prefetch this consumer is holding is still counted here -- which is worth asserting
        // both ways round, because "green has the backlog" and "somebody on green is working
        // through it" are two different claims and the second one is what verify passed on.
        assertThat(depth(GREEN, "orders.new")).isEqualTo(ORDERS);
        assertThat(unackedOnGreen()).isPositive();

        // The queue the file deliberately left out of the drain's patterns. Its messages never
        // moved, and green has the queue because a topology copy copies the whole shape while the
        // drain's patterns narrow what is *moved* rather than what is created.
        assertThat(depth(BLUE, "orders.audit")).isEqualTo(ORDERS + PRIORITY);
        assertThat(names(GREEN)).contains("orders.audit");
        assertThat(depth(GREEN, "orders.audit")).isZero();
    }

    @Test
    @Order(5)
    @Timeout(120)
    @DisplayName("takes the backup, copies the shape, and lands the policies after the drain")
    void theEstateWasCopied() throws IOException {
        assertThat(names(GREEN)).contains("orders.new", "orders.priority", "svc.heartbeat");
        assertThat(policyNames(GREEN)).contains("capped");

        try (Stream<Path> written = Files.list(work.resolve("backups"))) {
            List<Path> files = written.toList();
            assertThat(files).singleElement().asString().endsWith(".json");
            // Redacted by default, which is the trade docs/message-state.md names: the file cannot
            // restore a cluster on its own, and it is not a credential on somebody's laptop.
            assertThat(Files.readString(files.get(0))).contains("orders.new")
                    .doesNotContain("password_hash");
        }
    }

    // ---------------------------------------------------------------- the platform team

    /**
     * What happens when somebody switches the CNAME: the clients turn up on the other cluster.
     *
     * <p>A prefetch and no acknowledgement, so the application is holding work rather than having
     * finished it. The verify step is looking for a consumer on green and this is the consumer.
     */
    private static void applicationArrivesOnGreen() {
        greenConsumer = Lab.connect(GREEN, GREEN.getAdminUsername(), GREEN.getAdminPassword(),
                "orders-service");
        try {
            Channel channel = greenConsumer.createChannel();
            channel.basicQos(25);
            channel.basicConsume("orders.new", false, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    PROCESSED_ON_GREEN.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                }
            });
        } catch (IOException broken) {
            throw new IllegalStateException("the application could not reach green", broken);
        }
        eventually("green reports the application that followed the endpoint",
                () -> Lab.consumers(GREEN).stream()
                        .anyMatch(consumer -> "orders.new".equals(consumer.queue())));
    }

    private static long unackedOnGreen() {
        return Lab.unacked(GREEN, "orders.new");
    }

    // ---------------------------------------------------------------- the file

    private static String yaml() {
        return """
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: lab-orders

                provider: rabbitmq

                clusters:
                  blue:
                    management: %s
                    amqp: %s
                    vhost: /
                    username: %s
                    password: %s
                  green:
                    management: %s
                    amqp: %s
                    vhost: /
                    username: %s
                    password: %s

                endpoint:
                  kind: external
                  description: lab-orders.internal is a CNAME the platform team switches.

                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce

                  backup:
                    enabled: true
                    path: %s

                  announce:
                    exchange: deploy.events
                    routingKey: ''
                    payload: '{"deployment":"lab-orders","status":"started"}'

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
                        vhosts: ["/"]
                        include: [exchanges, queues, bindings]
                        exclude: [policies, operatorPolicies]

                    - id: announce-drain
                      announce: {}

                    - id: pause-producers
                      waitFor:
                        on: blue
                        publishRate: 0
                        timeout: 5m
                        onTimeout: abort

                    - id: drain-consumers
                      closeConnections:
                        on: blue
                        select:
                          role: consumer
                        after:
                          unacked: 0
                          timeout: 3m
                          onTimeout: abort
                      waitFor:
                        on: blue
                        unacked: 0
                        timeout: 3m
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
                        timeout: 5m
                        onTimeout: abort

                    - id: policies
                      copyTopology:
                        from: blue
                        to: green
                        vhosts: ["/"]
                        include: [policies, operatorPolicies]

                    - id: switch-endpoint
                      endpoint:
                        target: green

                    - id: verify
                      waitFor:
                        on: green
                        consumers:
                          min: 1
                        timeout: 3m
                        onTimeout: abort
                """.formatted(management(BLUE), amqp(BLUE, "blue"), BLUE.getAdminUsername(),
                BLUE.getAdminPassword(), management(GREEN), amqp(GREEN, "green"),
                GREEN.getAdminUsername(), GREEN.getAdminPassword(),
                work.resolve("backups").resolve("{{name}}-{{timestamp}}.json"));
    }
}
