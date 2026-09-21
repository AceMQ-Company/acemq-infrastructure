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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The probe, against a broker that exists.
 *
 * <p>Testcontainers rather than a flag that skips when Docker is absent, for the reason
 * {@code etc/check-nothing-skipped.py} is in this repository at all: a skip reads as a pass, and
 * the tests most worth having are the ones that need the thing that is hardest to arrange.
 *
 * <p>Everything a probe reads is here made true first and then read back — a topology, a policy, a
 * stream, a live consumer, and a second user with only the monitoring tag. The last of those is
 * the case that matters most and is least likely to be arranged by accident: a read-only account
 * on a cluster where every plugin is enabled probes perfectly well and cannot do half of what the
 * plan would ask of it.
 */
@Testcontainers
@DisplayName("probing a real cluster")
class RabbitProbeIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            // The image ships these and does not enable them. That is exactly the capability
            // question docs/broker-agnostic.md is about, and MissingPluginsIT is the other half
            // of it: the same image, untouched, where the drain cannot run.
            .withPluginsEnabled("rabbitmq_shovel", "rabbitmq_shovel_management",
                    "rabbitmq_federation", "rabbitmq_federation_management");

    /** A user with the monitoring tag and nothing else, which is the interesting estate. */
    private static final String WATCHER = "watcher";

    private static String url;
    private static Connection consuming;

    @BeforeAll
    static void setUp() throws Exception {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);
        try (RabbitAdmin admin = RabbitAdmin.connect(url, BROKER.getAdminUsername(),
                BROKER.getAdminPassword())) {
            admin.declareExchange("orders", "topic", true, Map.of());
            admin.declareQueue("orders.new", true, Map.of());
            admin.declareQueue("orders.audit", true, Map.of());
            admin.declareQueue("orders.priority", true, Map.of("x-queue-type", "quorum"));
            // A stream, because a stream in the drain's scope is the one thing that stops a plan.
            admin.declareQueue("orders.events", true, Map.of("x-queue-type", "stream"));
            admin.bindQueue("orders", "orders.new", "order.created", Map.of());
            admin.bindQueue("orders", "orders.audit", "#", Map.of());
            admin.putPolicy("short-ttl", "^orders\\.new$", Map.of("message-ttl", 600_000), 1);
            admin.putOperatorPolicy("capped", "^orders\\.", Map.of("max-length", 1_000_000), 1);
            admin.createUser(WATCHER, "watcher", "monitoring");
            admin.grant(WATCHER, ".*", ".*", ".*");
        }

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(BROKER.getHost());
        factory.setPort(BROKER.getAmqpPort());
        factory.setUsername(BROKER.getAdminUsername());
        factory.setPassword(BROKER.getAdminPassword());
        consuming = factory.newConnection("orders-service");
        consuming.createChannel().basicConsume("orders.new", true,
                new DefaultConsumer(consuming.createChannel()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (consuming != null && consuming.isOpen()) {
            consuming.close();
        }
    }

    private static ProbedCluster probe(String user, String password) {
        return new RabbitProbe().probe(ClusterAccess.to("blue", url, "/", user, password));
    }

    private static ProbedCluster probe() {
        return probe(BROKER.getAdminUsername(), BROKER.getAdminPassword());
    }

    /**
     * The management API's connection, channel and queue numbers come from the statistics
     * database, which refreshes on an interval rather than on every event. A test that asserted
     * immediately would be asserting against numbers from before it did anything.
     */
    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the broker never reported what the test was waiting for");
    }

    @Nested
    @DisplayName("what the cluster is")
    class Identity {

        @Test
        @Timeout(180)
        @DisplayName("reports the product and the version the broker gave")
        void version() {
            ProbedCluster cluster = probe();
            assertThat(cluster.name()).isEqualTo("blue");
            assertThat(cluster.release()).startsWith("RabbitMQ 4.");
        }

        @Test
        @Timeout(180)
        @DisplayName("reports the plugins that are enabled, by asking their endpoints")
        void facilities() {
            assertThat(probe().facilityMarks()).isEqualTo("shovel✓ federation✓ streams✓");
        }

        @Test
        @Timeout(180)
        @DisplayName("says that credentials crossing the network in the clear do")
        void httpIsNoted() {
            assertThat(probe().notes())
                    .anyMatch(note -> note.contains("http") && note.contains("in the clear"));
        }
    }

    @Nested
    @DisplayName("what it can do")
    class Capabilities {

        @Test
        @Timeout(180)
        @DisplayName("an administrator on a current broker with every plugin: everything")
        void administrator() {
            assertThat(probe().present()).containsExactlyInAnyOrder(Capability.values());
        }

        @Test
        @Timeout(180)
        @DisplayName("a monitoring-only account can watch and cannot act")
        void monitoringOnly() {
            ProbedCluster cluster = probe(WATCHER, "watcher");

            // It can see everything the plan reads.
            assertThat(cluster.can(Capability.CONSUMER_INSPECT)).isTrue();
            assertThat(cluster.can(Capability.QUEUE_ARGUMENTS)).isTrue();

            // And it can do none of what the plan would ask, on a cluster where the plugins are
            // all enabled -- which is the point. Nothing about this estate looks wrong until the
            // drain.
            assertThat(cluster.can(Capability.CONNECTION_CLOSE)).isFalse();
            assertThat(cluster.can(Capability.TOPOLOGY_IMPORT_MERGE)).isFalse();
            assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isFalse();
            assertThat(cluster.can(Capability.OPERATOR_POLICY)).isFalse();
            assertThat(cluster.whyNot(Capability.DRAIN_BY_SHOVEL))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("monitoring"));
        }

        @Test
        @Timeout(180)
        @DisplayName("a monitoring-only account cannot export the definitions either")
        void monitoringCannotExport() {
            // Proved by trying to read them, rather than concluded from the tag: this is the one
            // capability whose answer the probe can establish outright.
            assertThat(probe(WATCHER, "watcher").can(Capability.TOPOLOGY_EXPORT)).isFalse();
            assertThat(probe().can(Capability.TOPOLOGY_EXPORT)).isTrue();
        }
    }

    @Nested
    @DisplayName("what is on it")
    class Counted {

        @Test
        @Timeout(180)
        @DisplayName("counts the topology the plan is going to copy")
        void topology() {
            Inventory inventory = probe().inventory();
            assertThat(inventory.exchanges()).isGreaterThanOrEqualTo(1);
            assertThat(inventory.bindings()).isGreaterThanOrEqualTo(2);
            assertThat(inventory.policies()).isEqualTo(1);
            assertThat(inventory.operatorPolicies()).isEqualTo(1);
            assertThat(inventory.users()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @Timeout(180)
        @DisplayName("names every queue, with the type the broker holds it as")
        void queues() {
            List<Inventory.Queue> queues = probe().inventory().queues();
            assertThat(queues).extracting(Inventory.Queue::name)
                    .contains("orders.new", "orders.audit", "orders.priority", "orders.events");
            assertThat(queues).filteredOn(queue -> queue.name().equals("orders.priority"))
                    .singleElement().extracting(Inventory.Queue::type).isEqualTo("quorum");
        }

        @Test
        @Timeout(180)
        @DisplayName("finds the stream, which is what makes a plan stop and ask")
        void streams() {
            assertThat(probe().inventory().streams())
                    .extracting(Inventory.Queue::name).containsExactly("orders.events");
        }

        @Test
        @Timeout(180)
        @DisplayName("joins channels to connections, so that a close step can be counted")
        void consumingConnections() throws InterruptedException {
            // The management API answers "which connection is this channel on" and "how many
            // consumers are on this channel", and never "which connections are consuming".
            eventually(() -> probe().inventory().connections().stream()
                    .anyMatch(Inventory.Connection::consuming));
            assertThat(probe().inventory().connections())
                    .filteredOn(Inventory.Connection::consuming)
                    .isNotEmpty()
                    .allSatisfy(connection ->
                            assertThat(connection.user()).isEqualTo(BROKER.getAdminUsername()));
        }
    }

    @Nested
    @DisplayName("and the point of the whole milestone")
    class WritesNothing {

        /**
         * The last clause of docs/roadmap.md's sentence for milestone one, asserted against a real
         * broker rather than argued for in a comment.
         *
         * <p>The structural arguments are elsewhere — the planner has no client on its classpath,
         * and the probe holds a {@link ReadOnlyAdmin} with no writing method on it. This is the
         * observation that those arguments produce the behaviour they claim: take the cluster's
         * definitions document, probe it four times over, take it again, and compare.
         */
        @Test
        @Timeout(180)
        @DisplayName("probing changes nothing a definitions export can see")
        void definitionsAreUnchanged() {
            String before = definitions();
            probe();
            probe(WATCHER, "watcher");
            probe();
            probe();
            assertThat(definitions()).isEqualTo(before);
        }

        @Test
        @Timeout(180)
        @DisplayName("probing does not close the connection it is counting")
        void connectionsSurvive() {
            probe();
            probe();
            assertThat(consuming.isOpen()).isTrue();
        }

        private String definitions() {
            try (RabbitAdmin admin = RabbitAdmin.connect(url, BROKER.getAdminUsername(),
                    BROKER.getAdminPassword())) {
                return admin.exportDefinitions().json();
            }
        }
    }
}
