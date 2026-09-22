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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.MessageProperties;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFileParser;
import org.acemq.infra.config.Environment;
import org.acemq.infra.execute.rabbitmq.RabbitBroker;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.ConsumerInfo;
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
 * A canary: one workload moves, another on the same cluster does not, and a stranger refuses it.
 *
 * <p>docs/canary.md argues that a broker canary is a blue/green cutover at a smaller scope, and
 * that sharing the machinery is a feature rather than an admission. {@link BlueGreenCutoverIT}
 * already proves the machinery against two real clusters, so what is left for this test is the two
 * things a canary adds, and it asserts very little else.
 *
 * <ol>
 *   <li><strong>The scope really is smaller.</strong> Not "the run completed" — that a second
 *       workload, on the same cluster in the same virtual host, consumed by a different service,
 *       was genuinely untouched: the same messages, the same connection, never closed, still
 *       consuming, and its copy on the target still empty. A canary that quietly drained the estate
 *       would pass a test that only looked at the queue it was asked to move.</li>
 *   <li><strong>The check refuses.</strong> An unlisted consumer is attached to a scoped queue and
 *       the run stops before the first write. That check is the deliverable: without it a canary is
 *       a partitioned queue with a smaller blast radius, which is what the whole page is about.</li>
 * </ol>
 *
 * <p>The refusal runs <em>first</em>, deliberately. Afterwards it would be checking a refusal
 * against an estate the cutover had already emptied, which is a weaker claim than the one worth
 * making: nothing was written to either cluster, and the estate the cutover then moves is the
 * estate the refusal looked at.
 *
 * <h2>Three services, and they are three broker users</h2>
 *
 * <p>The scope's {@code services:} list is matched against the user a connection authenticated as,
 * for the same reason the close step's selector is: the broker has no concept of a service and the
 * user is the only identity it carries from the client to the management API. So the lab has three
 * real users with real permissions rather than one admin account wearing three hats — an estate
 * where every application authenticates as the same user is one where the check cannot tell them
 * apart, and the test would pass without proving anything.
 *
 * <h2>The estate, and why it is shaped like this</h2>
 *
 * <p>Every queue here is either something a consumer keeps empty or something with a backlog and
 * nobody on it, because those are the two states a queue is actually in and because a consumer
 * attached to the backlog under test would eat the thing being measured.
 *
 * <ul>
 *   <li>{@code orders.notifications} — in scope, empty, {@code notification-service} keeping up.</li>
 *   <li>{@code orders.notifications.dlq} — in scope, a backlog nobody has triaged. What moves.</li>
 *   <li>{@code orders.new} — out of scope, a backlog with {@code orders-service} on it holding one
 *       delivery unsettled. The unsettled delivery is not decoration: a whole-estate cutover's
 *       {@code unacked: 0} guard would wait out its timeout on it, and a canary's does not,
 *       because a canary measures its scope.</li>
 *   <li>{@code orders.audit} — out of scope, a backlog with nobody on it.</li>
 * </ul>
 *
 * <p>As in {@link BlueGreenCutoverIT}, the {@link ProbedCluster} is assembled here from a live
 * listing rather than taken from {@code RabbitProbe}: {@code acemq-infra-execute} does not depend
 * on {@code acemq-infra-rabbitmq} and must not, because that separation is the whole of "the thing
 * that plans holds a client that cannot write".
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a canary: one workload moves and the rest of the estate does not")
class CanaryCutoverIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:4-management");

    /** The one thing the two clusters share, and the only reason a shovel can cross. */
    private static final Network LAB = Network.newNetwork();

    @Container
    private static final RabbitMQContainer BLUE = broker("blue");

    @Container
    private static final RabbitMQContainer GREEN = broker("green");

    /** In scope: the live queue, which its consumer keeps empty. */
    private static final String MOVES = "orders.notifications";

    /** In scope: the dead-letter queue, which is where the backlog actually is. */
    private static final String MOVES_DLQ = "orders.notifications.dlq";

    /** Out of scope: another service's queue, on the same cluster, with a slow consumer on it. */
    private static final String STAYS = "orders.new";

    /** Out of scope: a backlog with nobody on it, which must be exactly where it was afterwards. */
    private static final String STAYS_AUDIT = "orders.audit";

    private static final int DEAD_LETTERS = 40;
    private static final int ORDERS = 90;
    private static final int AUDIT = 25;

    private static final String NOTIFIER = "notification-service";
    private static final String ORDERS_SERVICE = "orders-service";

    /** The consumer nobody wrote down, which is what the refusal is about. */
    private static final String STRANGER = "legacy-mailer";

    private static Connection notifier;
    private static Connection ordersService;
    private static Connection stranger;

    /** The one service that follows the endpoint, which is what the verify step looks for. */
    private static Connection notifierOnGreen;

    /** Where the backup lands, so a real file is written and nobody's working directory is. */
    @TempDir
    private static java.nio.file.Path backups;

    private static Execution canary;

    private static RabbitMQContainer broker(String alias) {
        return new RabbitMQContainer(IMAGE)
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
            for (String queue : new String[] {MOVES, MOVES_DLQ, STAYS, STAYS_AUDIT}) {
                admin.declareQueue(queue, true, Map.of());
            }
            admin.bindQueue("orders", MOVES_DLQ, "order.notify.dead", Map.of());
            admin.bindQueue("orders", STAYS, "order.created", Map.of());
            admin.bindQueue("orders", STAYS_AUDIT, "order.audit", Map.of());

            for (String service : new String[] {NOTIFIER, ORDERS_SERVICE, STRANGER}) {
                admin.createUser(service, "s3cret", "management");
                admin.grant(service, ".*", ".*", ".*");
            }
        }

        try (Connection publishing = connect(BLUE, BLUE.getAdminUsername(),
                BLUE.getAdminPassword());
                Channel channel = publishing.createChannel()) {
            publish(channel, "order.notify.dead", "dead-", DEAD_LETTERS);
            publish(channel, "order.created", "order-", ORDERS);
            publish(channel, "order.audit", "audit-", AUDIT);
        }

        notifier = keepingUp(BLUE, NOTIFIER, MOVES);
        ordersService = slowConsumer(BLUE, ORDERS_SERVICE, STAYS);
        stranger = keepingUp(BLUE, STRANGER, MOVES);

        eventually("blue reports all three consumers", () -> {
            List<Inventory.Consumer> attached = consumersOn(BLUE);
            return attached.stream().anyMatch(one -> one.user().equals(NOTIFIER))
                    && attached.stream().anyMatch(one -> one.user().equals(ORDERS_SERVICE))
                    && attached.stream().anyMatch(one -> one.user().equals(STRANGER));
        });
        // The statistics database refreshes on an interval rather than on every event, so a run
        // started immediately would be planned against an estate from before any of this happened.
        eventually("blue reports the backlogs and the unsettled delivery",
                () -> depth(BLUE, MOVES_DLQ) == DEAD_LETTERS && depth(BLUE, STAYS) == ORDERS
                        && depth(BLUE, STAYS_AUDIT) == AUDIT && unacked(BLUE, STAYS) == 1);
    }

    @AfterAll
    static void disconnect() throws Exception {
        for (Connection connection
                : new Connection[] {notifier, ordersService, stranger, notifierOnGreen}) {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        }
    }

    // ---------------------------------------------------------------- the refusal

    /**
     * The check this whole operation is built on, against a live estate.
     *
     * <p>{@code legacy-mailer} is consuming {@code orders.notifications} and the file does not name
     * it. Moving the queue anyway would leave that consumer attached to a queue on the cluster the
     * messages had just left — the partitioned queue docs/canary.md is about — so the run stops
     * before the first write, and says which consumer, on which connection, and what to do.
     */
    @Test
    @Order(1)
    @Timeout(600)
    @DisplayName("refuses while something the file did not name is consuming a scoped queue")
    void refusesAnUnlistedConsumer() throws Exception {
        Map<String, Long> before = depths(BLUE);

        Execution refused = run(Run.Mode.EXECUTE);
        System.out.println(refused.render());

        assertThat(refused.outcome()).isEqualTo(Execution.Outcome.REFUSED);
        assertThat(refused.render())
                .contains("'" + STRANGER + "' is consuming " + MOVES + " on blue")
                .contains("partitions it");

        // Nothing was written, which is the half of a refusal worth asserting. Green has not even
        // been given the shape: the preflight answers everything before the first step runs.
        assertThat(depths(BLUE)).isEqualTo(before);
        assertThat(names(GREEN)).doesNotContain(MOVES, MOVES_DLQ, STAYS);

        // And a rehearsal refuses it identically, so a dry run in a pull request is worth reading.
        // A rehearsal that reported this estate as fine would be the worst of both.
        assertThat(run(Run.Mode.REHEARSE).outcome()).isEqualTo(Execution.Outcome.REFUSED);

        stranger.close();
        eventually("blue stops reporting the unlisted consumer", () ->
                consumersOn(BLUE).stream().noneMatch(one -> one.user().equals(STRANGER)));
    }

    // ---------------------------------------------------------------- the canary

    @Test
    @Order(2)
    @Timeout(900)
    @DisplayName("moves the scoped workload once nothing unlisted is attached to it")
    void theCanary() {
        canary = run(Run.Mode.EXECUTE);
        System.out.println(canary.render());

        assertThat(canary.outcome()).isEqualTo(Execution.Outcome.COMPLETED);

        // The default canary list, which is the default blue/green list with a narrower selector
        // and a narrower drain. Asserted in order, because the order is the product.
        assertThat(canary.steps()).extracting(Execution.Taken::id).containsExactly(
                "backup", "topology", "announce-drain", "pause-producers", "drain-consumers",
                "drain-messages", "policies", "switch-endpoint", "verify");
        assertThat(canary.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(Execution.Status.DONE));
        assertThat(canary.movements()).isEmpty();

        // One connection closed, and it is the named service's. The other workload's consumer was
        // connected, consuming and holding an unsettled delivery the whole time.
        assertThat(lines("drain-consumers")).anyMatch(line -> line.contains("closed 1"));
    }

    @Test
    @Order(3)
    @Timeout(120)
    @DisplayName("leaves the scoped queues empty on blue and holding the backlog on green")
    void theScopedWorkloadMoved() {
        assertThat(depth(BLUE, MOVES)).isZero();
        assertThat(depth(BLUE, MOVES_DLQ)).isZero();
        assertThat(depth(GREEN, MOVES_DLQ)).isEqualTo(DEAD_LETTERS);
        assertThat(names(GREEN)).contains(MOVES, MOVES_DLQ);
    }

    /**
     * The assertion the whole test exists for.
     *
     * <p>A canary that completed and moved the entire estate would pass every check above this one.
     * What makes it a canary is that a second workload, on the same cluster and in the same virtual
     * host, is exactly where it was — not approximately, and not "still has most of its messages".
     */
    @Test
    @Order(4)
    @Timeout(120)
    @DisplayName("leaves the unmoved workload genuinely untouched")
    void theOtherWorkloadDidNotMove() {
        assertThat(depth(BLUE, STAYS)).isEqualTo(ORDERS);
        assertThat(depth(BLUE, STAYS_AUDIT)).isEqualTo(AUDIT);

        // Its consumer was never closed. The close step selects on the scope's services and this
        // connection belongs to somebody else, so it is the same connection, still open, still
        // consuming, and still holding the delivery it never settled.
        assertThat(ordersService.isOpen()).isTrue();
        assertThat(consumersOn(BLUE))
                .anyMatch(one -> one.user().equals(ORDERS_SERVICE) && one.queue().equals(STAYS));
        assertThat(unacked(BLUE, STAYS)).isEqualTo(1);

        // Green has both queues, because the topology copy copies the whole shape of the virtual
        // host and the scope narrows what is *moved* rather than what is created. Both empty, which
        // is what makes the lines above mean something: nothing shovelled them across.
        assertThat(names(GREEN)).contains(STAYS, STAYS_AUDIT);
        assertThat(depth(GREEN, STAYS)).isZero();
        assertThat(depth(GREEN, STAYS_AUDIT)).isZero();

        // The drain named the two scoped queues and nothing else, in the run's own words.
        assertThat(lines("drain-messages"))
                .anyMatch(line -> line.contains(MOVES) && line.contains(MOVES_DLQ));
        assertThat(String.join(" ", lines("drain-messages"))).doesNotContain(STAYS);
    }

    /**
     * The guards narrowed with the scope, which is the part that is easy to leave out.
     *
     * <p>{@code orders-service} held one delivery unsettled on {@code orders.new} for the whole of
     * the cutover, and {@code drain-consumers} waits for {@code unacked: 0}. A guard that measured
     * the virtual host would have waited out its five minutes on a workload this canary was
     * deliberately not touching and then aborted. That it passed is the assertion.
     */
    @Test
    @Order(5)
    @Timeout(60)
    @DisplayName("waits on the scoped queues rather than on the whole virtual host")
    void theGuardsMeasuredTheScope() {
        assertThat(lines("drain-consumers")).anyMatch(line -> line.contains("unacked=0"));
        assertThat(unacked(BLUE, STAYS)).isEqualTo(1);
    }

    /** The row docs/canary.md ends on, said before the run rather than at the endpoint step. */
    @Test
    @Order(6)
    @Timeout(60)
    @DisplayName("says up front that the endpoint has to move for one service only")
    void saysWhatTheEndpointHasToDo() {
        assertThat(canary.notes()).anyMatch(note ->
                note.contains("PER-SERVICE routing")
                        && note.contains(NOTIFIER + " must resolve to green")
                        && note.contains("everything else still resolves to blue"));
    }

    // ---------------------------------------------------------------- running it

    private static Execution run(Run.Mode mode) {
        try (Broker blue = RabbitBroker.open(access(BLUE));
                Broker green = RabbitBroker.open(access(GREEN))) {
            Run.Builder builder = Run.of(deployment())
                    .from(new Run.Side("blue", snapshotOf("blue", BLUE), blue, amqp("blue")))
                    .to(new Run.Side("green", snapshotOf("green", GREEN), green, amqp("green")))
                    .console(new PlatformTeam());
            return Executor.execute(
                    mode == Run.Mode.REHEARSE ? builder.rehearsal() : builder.cutover());
        }
    }

    private static DeploymentFile deployment() {
        String yaml = """
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: notifications-canary

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
                  description: notification-service resolves through a per-service mesh route.

                deployment:
                  operation: canary
                  from: blue
                  to: green
                  semantics: atLeastOnce

                  backup:
                    enabled: true
                    path: %s

                  # No steps: on purpose. The default canary list is where scope.queues becomes the
                  # drain's patterns and scope.services becomes the close step's selector, and a
                  # file that wrote its own steps would be testing the file rather than that.
                  scope:
                    vhost: /
                    queues: ["%s", "%s"]
                    services: [%s]
                """.formatted(management(BLUE), amqp("blue"), BLUE.getAdminUsername(),
                BLUE.getAdminPassword(), management(GREEN), amqp("green"),
                GREEN.getAdminUsername(), GREEN.getAdminPassword(),
                backups.resolve("{{name}}-{{timestamp}}.json"), MOVES, MOVES_DLQ, NOTIFIER);
        return DeploymentFileParser.parse(yaml, "notifications-canary.yaml",
                Environment.of(Map.of()));
    }

    /**
     * The platform team, and the one service that follows the endpoint when they move it.
     *
     * <p>A canary's endpoint switch is the narrow one, which docs/canary.md calls the piece of work
     * the canary actually depends on. Here it is played by hand: {@code notification-service}
     * reconnects to green and {@code orders-service} stays exactly where it is, which is what
     * per-service routing means when somebody actually does it.
     */
    private static final class PlatformTeam implements Console {

        @Override
        public void say(String line) {
            System.out.println(line);
        }

        @Override
        public Answer ask(String question) {
            System.out.println("[console] " + question);
            if (question.contains("Switch the endpoint to green") && notifierOnGreen == null) {
                notifierOnGreen = arriveOnGreen();
            }
            return Answer.PROCEED;
        }

        private Connection arriveOnGreen() {
            try {
                Connection connection = keepingUp(GREEN, NOTIFIER, MOVES);
                eventually("the application arrives on green", () ->
                        consumersOn(GREEN).stream().anyMatch(one -> one.user().equals(NOTIFIER)));
                return connection;
            } catch (Exception failed) {
                throw new IllegalStateException("the application could not reach green", failed);
            }
        }
    }

    // ---------------------------------------------------------------- reaching the clusters

    private static String management(RabbitMQContainer container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(15672);
    }

    /** A cluster's AMQP URI as the <em>other</em> broker will dial it, over the shared network. */
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

    /**
     * What the cluster is, right now, in the shape a run makes its refusals against.
     *
     * <p>The consumer listing is joined to the channel listing here for the same reason the
     * RabbitMQ probe does it: {@code /api/consumers} names the queue and the channel and never the
     * user, and the user is what a canary's {@code services:} list matches.
     */
    private static ProbedCluster snapshotOf(String name, RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            Inventory.Builder inventory = Inventory.counting()
                    .exchanges(admin.exchanges().size())
                    .bindings(admin.bindings().size())
                    .users(admin.users().size())
                    .permissions(admin.permissions().size());
            for (QueueInfo queue : admin.queues()) {
                inventory.queue(queue.name(), queue.type(), queue.messages(), queue.consumers());
            }
            Map<String, Integer> consuming = new HashMap<>();
            for (ChannelInfo channel : admin.channels()) {
                consuming.merge(channel.connectionName(), channel.consumerCount(), Integer::sum);
            }
            for (ConnectionInfo connection : admin.connections()) {
                inventory.connection(connection.name(), connection.user(),
                        consuming.getOrDefault(connection.name(), 0));
            }
            for (Inventory.Consumer consumer : consumers(admin)) {
                inventory.consumer(consumer.queue(), consumer.connection(), consumer.user());
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

    private static List<Inventory.Consumer> consumersOn(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return consumers(admin);
        }
    }

    private static List<Inventory.Consumer> consumers(RabbitAdmin admin) {
        Map<String, ChannelInfo> channels = new HashMap<>();
        for (ChannelInfo channel : admin.channels()) {
            channels.put(channel.name(), channel);
        }
        List<Inventory.Consumer> attached = new ArrayList<>();
        for (ConsumerInfo consumer : admin.consumers()) {
            ChannelInfo channel = channels.get(consumer.channelName());
            attached.add(new Inventory.Consumer(consumer.queue(),
                    channel == null ? consumer.channelName() : channel.connectionName(),
                    channel == null ? consumer.channelName() : channel.user(), Optional.empty()));
        }
        return attached;
    }

    // ---------------------------------------------------------------- reading and writing

    private static Connection connect(RabbitMQContainer container, String user, String password)
            throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(container.getHost());
        factory.setPort(container.getAmqpPort());
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setVirtualHost("/");
        // Recovery off, deliberately. A client that reconnects on its own would come straight back
        // to the cluster the cutover just closed it out of, which is the failure mode
        // docs/message-state.md describes -- a closed connection goes wherever the client resolves
        // the broker address to, and this tool does not own that. The test plays the endpoint by
        // hand instead, which is what the platform team does.
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection(user);
    }

    /** An application keeping up: it settles everything and the queue stays empty. */
    private static Connection keepingUp(RabbitMQContainer container, String user, String queue)
            throws Exception {
        Connection connection = connect(container, user, "s3cret");
        Channel channel = connection.createChannel();
        channel.basicConsume(queue, true, new DefaultConsumer(channel));
        return connection;
    }

    /**
     * An application that is behind: one delivery taken, never settled, and a backlog behind it.
     *
     * <p>Out of the canary's scope on purpose. It is the estate's worth of noise a whole-estate
     * cutover's {@code unacked: 0} guard would wait out and a canary's must not.
     */
    private static Connection slowConsumer(RabbitMQContainer container, String user, String queue)
            throws Exception {
        Connection connection = connect(container, user, "s3cret");
        Channel channel = connection.createChannel();
        channel.basicQos(1);
        channel.basicConsume(queue, false, new DefaultConsumer(channel));
        return connection;
    }

    private static void publish(Channel channel, String key, String prefix, int many)
            throws Exception {
        for (int number = 0; number < many; number++) {
            channel.basicPublish("orders", key, MessageProperties.PERSISTENT_TEXT_PLAIN,
                    (prefix + number).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static long depth(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messages).orElse(0L);
    }

    private static long unacked(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messagesUnacknowledged).orElse(0L);
    }

    private static Optional<QueueInfo> queue(RabbitMQContainer container, String name) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().filter(one -> one.name().equals(name)).findFirst();
        }
    }

    /** Every queue's depth, for the before-and-after a refusal has to be measured by. */
    private static Map<String, Long> depths(RabbitMQContainer container) {
        Map<String, Long> held = new HashMap<>();
        try (RabbitAdmin admin = admin(container)) {
            admin.queues().forEach(queue -> held.put(queue.name(), queue.messages()));
        }
        return held;
    }

    private static List<String> names(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().map(QueueInfo::name).toList();
        }
    }

    /** The lines one step of the canary reported. */
    private static List<String> lines(String id) {
        return canary.steps().stream().filter(step -> step.id().equals(id))
                .flatMap(step -> step.lines().stream()).toList();
    }

    /**
     * Waits for something the statistics database has to catch up with.
     *
     * <p>The same hazard the guards are built around, and it applies to a test's own assertions
     * exactly as much. The consumer listing is the one this test leans on hardest: a canary planned
     * immediately after a consumer attached would be planned against a listing taken before it did.
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
