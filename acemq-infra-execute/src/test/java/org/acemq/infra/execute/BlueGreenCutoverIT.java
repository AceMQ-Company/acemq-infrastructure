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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.MessageProperties;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFileParser;
import org.acemq.infra.config.Environment;
import org.acemq.infra.config.Step;
import org.acemq.infra.execute.rabbitmq.RabbitBroker;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.rabbitmq.admin.PolicyInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
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
import org.testcontainers.utility.DockerImageName;

/**
 * A cutover, and then the rollback that undoes it, against two clusters that share nothing.
 *
 * <p>docs/roadmap.md puts this test in phase 2 and says why: a cutover without a tested rollback is
 * not finished. What it asks for is not "it did not throw" — it is that the estate is back where it
 * started and that <strong>what a rollback duplicated has been counted</strong>. The file says
 * {@code semantics: atLeastOnce}, and the honest reading of those two words is that some messages
 * are processed on both clusters. Nobody should be asked to trust a rollback without being told how
 * many, so this test arranges for the duplication to happen, measures it, and prints the number.
 *
 * <h2>The lab, as containers</h2>
 *
 * <p>The same estate scripts/blue-green-lab.sh builds, and for the reason that script gives: two
 * <em>separate</em> single-node clusters rather than two nodes of one. A cutover moves an estate
 * between clusters that share no Erlang cookie, no membership and no storage, and two nodes of one
 * cluster would let a whole class of mistake pass that production would not. They share a Docker
 * network and nothing else, because a shovel runs inside a broker and has to be able to dial the
 * other one — which is also why {@link Run.Side} takes the AMQP URI as the far broker sees it and
 * not as this JVM does.
 *
 * <p>Testcontainers rather than a flag that skips when Docker is absent, for the reason
 * {@code etc/check-nothing-skipped.py} exists: a skip reads as a pass, and the test most worth
 * having is the one that needs the thing hardest to arrange.
 *
 * <h2>Why the probe here is assembled rather than taken</h2>
 *
 * <p>{@link ProbedCluster} is built from a live listing of each broker instead of from
 * {@code RabbitProbe}, and that is the module split showing rather than a shortcut.
 * {@code acemq-infra-execute} does not depend on {@code acemq-infra-rabbitmq} and must not: the
 * whole of "the probe holds a client that cannot write" is that the two modules cannot see each
 * other. {@code RabbitProbeIT} is where the probe is tested against a real broker. What this test
 * needs of a {@link ProbedCluster} is the queue list a drain resolves its patterns against, and
 * that is read off the cluster here so the selection is made against what is actually there.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a cutover, and the rollback that undoes it")
class BlueGreenCutoverIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:4-management");

    /** The one thing the two clusters share, and the only reason a shovel can cross. */
    private static final Network LAB = Network.newNetwork();

    @Container
    private static final RabbitMQContainer BLUE = broker("blue");

    @Container
    private static final RabbitMQContainer GREEN = broker("green");

    /** How many messages the workload left on {@code orders.new}. */
    private static final int ORDERS = 120;

    /** And on {@code orders.priority}, so that no drained queue starts empty. */
    private static final int PRIORITY = 20;

    /**
     * How many messages the application on green has in flight when the rollback is called for.
     *
     * <p>This is the number the whole test is built around. A consumer that has been handed a
     * message and has not settled it has <em>processed</em> it — the handler ran, the row was
     * written, the email went out — and closing that connection requeues it on green, from where the
     * rollback carries it back to blue and hands it to somebody a second time. That is what
     * {@code atLeastOnce} costs, and it is a number rather than a warning.
     */
    private static final int IN_FLIGHT = 25;

    /** Where the backup lands, so that a real file is written and nobody's working directory is. */
    @TempDir
    private static Path backups;

    private static Connection blueConsumer;
    private static Connection greenConsumer;

    /** The ids the application on green was handed and never settled. */
    private static final ConcurrentLinkedQueue<String> PROCESSED_ON_GREEN =
            new ConcurrentLinkedQueue<>();

    /** The cutover's report, kept so that the rollback can be derived from what it actually did. */
    private static Execution cutover;

    private static RabbitMQContainer broker(String alias) {
        return new RabbitMQContainer(IMAGE)
                // The image ships the plugins and does not enable them. A cutover cannot happen
                // without both, which is the capability question docs/broker-agnostic.md is about.
                .withPluginsEnabled("rabbitmq_shovel", "rabbitmq_shovel_management",
                        "rabbitmq_federation", "rabbitmq_federation_management")
                .withNetwork(LAB)
                .withNetworkAliases(alias);
    }

    // ---------------------------------------------------------------- the estate

    @BeforeAll
    static void seed() throws Exception {
        try (RabbitAdmin admin = admin(BLUE)) {
            admin.declareExchange("orders", "topic", true, Map.of());
            admin.declareExchange("deploy.events", "fanout", true, Map.of());
            admin.declareQueue("orders.new", true, Map.of());
            admin.declareQueue("orders.audit", true, Map.of());
            admin.declareQueue("orders.priority", true, Map.of());
            // Outside the drain's patterns on purpose, and the queue the workload on blue sits on.
            // A consumer has to be somewhere for the close step to close it, and putting it on a
            // queue holding the backlog would mean the test's own consumer ate what the drain was
            // supposed to move.
            admin.declareQueue("svc.heartbeat", true, Map.of());
            admin.declareQueue("deploy.log", true, Map.of());
            admin.bindQueue("orders", "orders.new", "order.created", Map.of());
            admin.bindQueue("orders", "orders.priority", "order.priority", Map.of());
            admin.bindQueue("orders", "orders.audit", "#", Map.of());
            admin.bindQueue("deploy.events", "deploy.log", "", Map.of());
            // A policy, so that the half of the topology copy which happens after the drain has
            // something to carry. Max-length rather than the lab's message-ttl: what the split
            // protects against is tested in RabbitTopologyTest against a document, and a ten-second
            // TTL in a test whose clock is a container start would make the counts a coin toss.
            admin.putPolicy("capped", "^orders\\.", Map.of("max-length", 1_000_000), 1);
        }

        try (Connection publishing = connect(BLUE); Channel channel = publishing.createChannel()) {
            for (int number = 0; number < ORDERS; number++) {
                publish(channel, "order.created", "order-" + number);
            }
            for (int number = 0; number < PRIORITY; number++) {
                publish(channel, "order.priority", "priority-" + number);
            }
        }

        // The workload on blue: connected, consuming, and about to be closed by step 5.
        blueConsumer = connect(BLUE);
        Channel heartbeat = blueConsumer.createChannel();
        heartbeat.basicConsume("svc.heartbeat", true, new DefaultConsumer(heartbeat));

        // The statistics database is what a close step reads to tell a consumer from a publisher,
        // and it refreshes on an interval rather than on every event -- so a run started
        // immediately would find nothing to close and report "close 0 connections", passing while
        // proving nothing. scripts/blue-green-lab.sh wrote this hazard down; it applies here too.
        eventually("blue reports a consuming connection", () -> {
            try (RabbitAdmin admin = admin(BLUE)) {
                return admin.channels().stream().anyMatch(channel -> channel.consumerCount() > 0);
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

    // ---------------------------------------------------------------- the cutover

    @Test
    @Order(1)
    @Timeout(900)
    @DisplayName("moves the estate to green, and says so step by step")
    void theCutover() {
        DeploymentFile file = deployment();

        try (Broker blue = RabbitBroker.open(access(BLUE));
                Broker green = RabbitBroker.open(access(GREEN))) {
            cutover = Executor.execute(Run.of(file)
                    .from(new Run.Side("blue", snapshotOf("blue", BLUE), blue, amqp("blue")))
                    .to(new Run.Side("green", snapshotOf("green", GREEN), green, amqp("green")))
                    .console(new PlatformTeam())
                    .cutover());
        }

        System.out.println(cutover.render());
        assertThat(cutover.outcome()).isEqualTo(Execution.Outcome.COMPLETED);

        // Every step, in the order docs/blue-green.md numbers them, and nothing left declared.
        assertThat(cutover.steps()).extracting(Execution.Taken::id).containsExactly(
                "backup", "topology", "announce-drain", "pause-producers", "drain-consumers",
                "drain-messages", "policies", "switch-endpoint", "verify");
        assertThat(cutover.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(Execution.Status.DONE));
        assertThat(cutover.movements()).isEmpty();

        // The workload on blue was found and closed, which is the verb the whole ordering is built
        // around: an unsettled delivery is requeued on the cluster being left, not on the one the
        // messages are going to.
        assertThat(lines("drain-consumers")).anyMatch(line -> line.contains("closed 1"));
    }

    @Test
    @Order(2)
    @Timeout(120)
    @DisplayName("leaves blue empty of everything the drain selected, and green holding it")
    void theMessagesMoved() {
        assertThat(depth(BLUE, "orders.new")).isZero();
        assertThat(depth(BLUE, "orders.priority")).isZero();
        assertThat(depth(GREEN, "orders.new")).isEqualTo(ORDERS);
        assertThat(depth(GREEN, "orders.priority")).isEqualTo(PRIORITY);

        // The queue the file deliberately left out of the drain's patterns. Its messages never
        // moved, and that is the line an operator reading the plan checks. Green has the queue,
        // because the topology copy copies the whole shape and the drain's patterns narrow what is
        // *moved* rather than what is created -- which is the arrangement somebody expects and
        // which is also the one that makes the emptiness below mean something.
        assertThat(depth(BLUE, "orders.audit")).isEqualTo(ORDERS + PRIORITY);
        assertThat(names(GREEN)).contains("orders.audit");
        assertThat(depth(GREEN, "orders.audit")).isZero();
    }

    @Test
    @Order(3)
    @Timeout(120)
    @DisplayName("takes the backup, copies the shape, and lands the policies after the drain")
    void theEstateWasCopied() throws IOException {
        assertThat(names(GREEN)).contains("orders.new", "orders.priority", "svc.heartbeat");
        assertThat(policyNames(GREEN)).contains("capped");

        try (java.util.stream.Stream<Path> written = Files.list(backups)) {
            List<Path> files = written.toList();
            assertThat(files).singleElement().asString().endsWith(".json");
            // Redacted by default, which is the trade docs/message-state.md names: the file cannot
            // restore a cluster on its own, and it is not a credential on somebody's laptop.
            assertThat(Files.readString(files.get(0))).contains("orders.new")
                    .doesNotContain("password_hash");
        }

        // Published to an exchange something is bound to, which is the half of an announcement
        // worth having: one routed nowhere is accepted by the broker and heard by nobody.
        assertThat(lines("announce-drain")).anyMatch(line -> line.contains("routed to at least"));
    }

    // ---------------------------------------------------------------- the rollback

    @Test
    @Order(4)
    @Timeout(900)
    @DisplayName("is undone by a second cutover in the other direction, derived from what ran")
    void theRollback() throws Exception {
        List<Step> rollback = cutover.rollback();

        // Derived from the steps that actually reached done, in reverse, and not from the plan.
        // The endpoint comes back first: bringing the clients back before the messages means they
        // reconnect to a cluster that is empty and stays empty until the drain-back finishes.
        assertThat(rollback).extracting(Step::describeId)
                .containsExactly("switch-endpoint", "drain-back");

        // The application follows the endpoint back, and this is the moment the duplication is
        // created rather than merely risked: everything this consumer was handed and never settled
        // is requeued on green now, and the drain-back is about to carry it to blue.
        assertThat(PROCESSED_ON_GREEN).hasSize(IN_FLIGHT);
        greenConsumer.close();
        eventually("green requeues what the application never settled",
                () -> unacked(GREEN, "orders.new") == 0 && depth(GREEN, "orders.new") == ORDERS);

        Execution undone;
        try (Broker blue = RabbitBroker.open(access(BLUE));
                Broker green = RabbitBroker.open(access(GREEN))) {
            undone = Executor.execute(Run.of(deployment())
                    .from(new Run.Side("blue", snapshotOf("blue", BLUE), blue, amqp("blue")))
                    .to(new Run.Side("green", snapshotOf("green", GREEN), green, amqp("green")))
                    .steps(rollback)
                    .console(new PlatformTeam())
                    .cutover());
        }

        System.out.println(undone.render());
        assertThat(undone.outcome()).isEqualTo(Execution.Outcome.COMPLETED);
        assertThat(undone.movements()).isEmpty();
    }

    @Test
    @Order(5)
    @Timeout(300)
    @DisplayName("puts the estate back where it started, and says what that cost")
    void theEstateIsWhereItStarted() throws Exception {
        assertThat(depth(GREEN, "orders.new")).isZero();
        assertThat(depth(GREEN, "orders.priority")).isZero();
        assertThat(depth(BLUE, "orders.new")).isEqualTo(ORDERS);
        assertThat(depth(BLUE, "orders.priority")).isEqualTo(PRIORITY);
        assertThat(depth(BLUE, "orders.audit")).isEqualTo(ORDERS + PRIORITY);

        // Green keeps the shape it was given, deliberately. Rollbacks does not invert a topology
        // copy, because the inverse of "write the source's shape onto the target" is not "delete
        // it" -- the cluster being returned to already has it, and deleting green's queues would
        // destroy anything published to green during the window.
        assertThat(names(GREEN)).contains("orders.new", "orders.priority");

        List<String> delivered = drain(BLUE, "orders.new");
        Set<String> distinct = new LinkedHashSet<>(delivered);
        Set<String> processedTwice = new HashSet<>(PROCESSED_ON_GREEN);
        processedTwice.retainAll(distinct);

        System.out.printf("""

                the cost of this rollback
                  %d messages seeded on blue, %d came back, %d of them distinct
                  %d were handed to the application on green and handed out again on blue
                  that is %.1f%% of the backlog processed on both clusters

                """, ORDERS, delivered.size(), distinct.size(), processedTwice.size(),
                100.0 * processedTwice.size() / ORDERS);

        // Nothing was lost, which is the first thing to be sure of.
        assertThat(distinct).hasSize(ORDERS);
        assertThat(delivered).hasSize(ORDERS);

        // And the number the file's `semantics: atLeastOnce` was promising. Asserted rather than
        // printed, and asserted to be more than nought on purpose: a test that reported zero
        // duplicates because nothing was ever in flight would be reporting on its own arrangement
        // rather than on the rollback.
        assertThat(processedTwice).hasSize(IN_FLIGHT);
    }

    // ---------------------------------------------------------------- the file

    private static DeploymentFile deployment() {
        String yaml = """
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
                """.formatted(management(BLUE), amqp("blue"), BLUE.getAdminUsername(),
                BLUE.getAdminPassword(), management(GREEN), amqp("green"),
                GREEN.getAdminUsername(), GREEN.getAdminPassword(),
                backups.resolve("{{name}}-{{timestamp}}.json"));
        return DeploymentFileParser.parse(yaml, "lab-orders.yaml", Environment.of(Map.of()));
    }

    /**
     * The platform team, and the application that follows the endpoint when they switch it.
     *
     * <p>An {@code endpoint: external} switch is the one step docs/blue-green.md says this tool
     * does not own: it stops, tells somebody what to change, and waits. Answering it here is how
     * the test plays that person — and attaching the consumer to green at that moment is how it
     * plays what happens next, which is the clients arriving on the other cluster. The step after
     * it is {@code verify}, whose whole job is to notice when they did not.
     */
    private static final class PlatformTeam implements Console {

        @Override
        public void say(String line) {
            System.out.println(line);
        }

        @Override
        public Answer ask(String question) {
            System.out.println("[console] " + question);
            if (question.contains("Switch the endpoint to green") && greenConsumer == null) {
                arriveOnGreen();
            }
            return Answer.PROCEED;
        }

        private void arriveOnGreen() {
            try {
                greenConsumer = connect(GREEN);
                Channel channel = greenConsumer.createChannel();
                // A prefetch and no acknowledgement: the application is handed this many and is
                // working on them when the rollback is called for. They are processed and unsettled,
                // which is the state the duplicate count is about.
                channel.basicQos(IN_FLIGHT);
                channel.basicConsume("orders.new", false, new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String tag, Envelope envelope,
                                               AMQP.BasicProperties properties, byte[] body) {
                        PROCESSED_ON_GREEN.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
                eventually("the application on green takes its prefetch",
                        () -> PROCESSED_ON_GREEN.size() >= IN_FLIGHT);
            } catch (Exception failed) {
                throw new IllegalStateException("the application could not reach green", failed);
            }
        }
    }

    // ---------------------------------------------------------------- reaching the clusters

    private static String management(RabbitMQContainer container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(15672);
    }

    /**
     * A cluster's AMQP URI <em>as the other broker will dial it</em>.
     *
     * <p>The container alias on the shared network, not the mapped port on this machine. A shovel
     * runs inside a broker; the address this JVM uses to reach a container is not an address the
     * other container has ever heard of, and getting this wrong is a shovel that never connects.
     */
    private static String amqp(String alias) {
        return "amqp://" + BLUE.getAdminUsername() + ":" + BLUE.getAdminPassword() + "@" + alias
                + ":5672";
    }

    private static ClusterAccess access(RabbitMQContainer container) {
        return ClusterAccess.to(container == BLUE ? "blue" : "green", management(container), "/",
                container.getAdminUsername(), container.getAdminPassword());
    }

    private static RabbitAdmin admin(RabbitMQContainer container) {
        return RabbitAdmin.connect(management(container), container.getAdminUsername(),
                container.getAdminPassword()).forVhost("/");
    }

    /** What the cluster is, right now, in the shape a run makes its refusals against. */
    private static ProbedCluster snapshotOf(String name, RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            Inventory.Builder inventory = Inventory.counting()
                    .exchanges(admin.exchanges().size())
                    .bindings(admin.bindings().size())
                    .policies(admin.policies().size());
            for (QueueInfo queue : admin.queues()) {
                inventory.queue(queue.name(), queue.type(), queue.messages(), queue.consumers());
            }
            return ProbedCluster.named(name)
                    .version(admin.version())
                    .facility("shovel", true)
                    .facility("federation", true)
                    .inventory(inventory.build())
                    .can(Capability.values())
                    .build();
        }
    }

    // ---------------------------------------------------------------- reading and writing

    private static Connection connect(RabbitMQContainer container) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(container.getHost());
        factory.setPort(container.getAmqpPort());
        factory.setUsername(container.getAdminUsername());
        factory.setPassword(container.getAdminPassword());
        return factory.newConnection("orders-service");
    }

    private static void publish(Channel channel, String key, String body) throws IOException {
        channel.basicPublish("orders", key, MessageProperties.PERSISTENT_TEXT_PLAIN,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Takes everything a queue holds, so that what came back can be counted by identity. */
    private static List<String> drain(RabbitMQContainer container, String queue) throws Exception {
        List<String> bodies = new ArrayList<>();
        try (Connection connection = connect(container); Channel channel = connection.createChannel()) {
            com.rabbitmq.client.GetResponse response;
            while ((response = channel.basicGet(queue, true)) != null) {
                bodies.add(new String(response.getBody(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return bodies;
    }

    private static long depth(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messages).orElse(0L);
    }

    private static long unacked(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messagesUnacknowledged).orElse(0L);
    }

    private static Optional<QueueInfo> queue(RabbitMQContainer container, String name) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().filter(queue -> queue.name().equals(name)).findFirst();
        }
    }

    private static List<String> names(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().map(QueueInfo::name).toList();
        }
    }

    private static List<String> policyNames(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.policies().stream().map(PolicyInfo::name).toList();
        }
    }

    /** The lines one step of the cutover reported. */
    private static List<String> lines(String id) {
        return cutover.steps().stream().filter(step -> step.id().equals(id))
                .flatMap(step -> step.lines().stream()).toList();
    }

    /**
     * Waits for something the statistics database has to catch up with.
     *
     * <p>The same hazard the guards are built around, and it applies to a test's own assertions
     * exactly as much: the management API's depths and counts refresh on
     * {@code collect_statistics_interval} rather than on every event, so a test that asserted
     * immediately would be asserting against numbers from before it did anything.
     */
    private static void eventually(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + what, interrupted);
            }
        }
        throw new AssertionError("waited ninety seconds and never saw: " + what);
    }
}
