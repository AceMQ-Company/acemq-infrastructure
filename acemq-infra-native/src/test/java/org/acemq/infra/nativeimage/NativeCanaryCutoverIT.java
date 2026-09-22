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
import static org.acemq.infra.nativeimage.Lab.unacked;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
 * The canary from {@code CanaryCutoverIT}, run by the binary, with nobody watching.
 *
 * <p>Where {@link NativeBlueGreenCutoverIT} puts a person at a terminal, this one is the other
 * shape entirely and the more common one: a pipeline, {@code --yes}, no console at all, and an
 * {@code endpoint: hook} that the estate supplied. That is exactly what the GitHub Action in
 * {@code action.yml} does, so this test is also the test of the Action's assumptions. The
 * difference matters for the native image specifically — a run with no terminal takes the other
 * branch of the one reflective call this repository writes, in {@code Terminal}, and both branches
 * are now exercised against a real binary.
 *
 * <h2>The hook as the thing that moves the clients</h2>
 *
 * <p>A canary's endpoint switch is the narrow one docs/canary.md calls the piece of work the canary
 * actually depends on: one service resolves to the target while everything else still resolves to
 * the source. Here the hook is a shell script that announces itself and then waits, which lets this
 * test play the estate on the other side of it — {@code notification-service} reconnects to green,
 * {@code orders-service} stays exactly where it is, and the tool carries on when the switch is
 * genuinely done rather than when it was merely asked for. That is what a hook with a timeout is
 * for, and it is the only way to test it without a human.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a canary, carried out by the binary with nobody watching")
class NativeCanaryCutoverIT {

    private static final Network LAB = Network.newNetwork();

    @Container
    private static final RabbitMQContainer BLUE = Lab.broker(LAB, "blue");

    @Container
    private static final RabbitMQContainer GREEN = Lab.broker(LAB, "green");

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

    private static final String SERVICE_PASSWORD = "s3cret";

    @TempDir
    private static Path work;

    private static Path deployment;

    /** Touched by the hook when the tool asks for the switch, and watched for by this test. */
    private static Path switching;

    /** Written by this test when the service has actually arrived, and waited for by the hook. */
    private static Path switched;

    private static Connection notifier;
    private static Connection ordersService;
    private static Connection stranger;
    private static Connection notifierOnGreen;

    // ---------------------------------------------------------------- the estate

    @BeforeAll
    static void seed() {
        try (RabbitAdmin admin = admin(BLUE)) {
            admin.declareExchange("orders", "topic", true, Map.of());
            for (String queue : new String[] {MOVES, MOVES_DLQ, STAYS, STAYS_AUDIT}) {
                admin.declareQueue(queue, true, Map.of());
            }
            admin.bindQueue("orders", MOVES_DLQ, "order.notify.dead", Map.of());
            admin.bindQueue("orders", STAYS, "order.created", Map.of());
            admin.bindQueue("orders", STAYS_AUDIT, "order.audit", Map.of());

            for (String service : new String[] {NOTIFIER, ORDERS_SERVICE, STRANGER}) {
                admin.createUser(service, SERVICE_PASSWORD, "management");
                admin.grant(service, ".*", ".*", ".*");
            }
        }

        try (Connection publishing = Lab.connect(BLUE, BLUE.getAdminUsername(),
                        BLUE.getAdminPassword(), "seed");
                Channel channel = publishing.createChannel()) {
            Lab.publish(channel, "orders", "order.notify.dead", "dead-", DEAD_LETTERS);
            Lab.publish(channel, "orders", "order.created", "order-", ORDERS);
            Lab.publish(channel, "orders", "order.audit", "audit-", AUDIT);
        } catch (Exception broken) {
            throw new IllegalStateException("could not seed blue", broken);
        }

        notifier = keepingUp(BLUE, NOTIFIER, MOVES);
        ordersService = slowConsumer(BLUE, ORDERS_SERVICE, STAYS);
        stranger = keepingUp(BLUE, STRANGER, MOVES);

        switching = work.resolve("the-platform-team-was-asked");
        switched = work.resolve("the-service-has-moved");
        deployment = Lab.file(work, "notifications-canary.yaml", yaml(hook()));

        eventually("blue reports all three consumers", () -> {
            var attached = Lab.consumingUsers(BLUE);
            return attached.contains(NOTIFIER) && attached.contains(ORDERS_SERVICE)
                    && attached.contains(STRANGER);
        });
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
     * The check the whole operation is built on, made by a binary against a live estate.
     *
     * <p>{@code legacy-mailer} is consuming a scoped queue and the file does not name it. Moving the
     * queue anyway would leave that consumer attached to a queue on the cluster the messages had
     * just left, which is the partitioned queue docs/canary.md is about. Asserted through a
     * rehearsal, because a rehearsal that reported this estate as fine and a real run that refused
     * it would be the worst of both — the dry run in the pull request is the artifact somebody acts
     * on.
     */
    @Test
    @Order(1)
    @Timeout(600)
    @DisplayName("refuses while something the file did not name is consuming a scoped queue")
    void refusesAnUnlistedConsumer() throws Exception {
        Map<String, Long> before = depths();

        Binary.Result refused = Binary.run("apply", "-f", deployment.toString(), "--dry-run");

        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.flat())
                .contains("'" + STRANGER + "' is consuming " + MOVES + " on blue")
                .contains("partitions it");

        // Nothing was written, which is the half of a refusal worth asserting. Green has not even
        // been given the shape: the preflight answers everything before the first step runs.
        assertThat(depths()).isEqualTo(before);
        assertThat(names(GREEN)).doesNotContain(MOVES, MOVES_DLQ, STAYS);

        stranger.close();
        eventually("blue stops reporting the unlisted consumer",
                () -> !Lab.consumingUsers(BLUE).contains(STRANGER));
    }

    // ---------------------------------------------------------------- the canary

    /**
     * The canary itself, started by something that is not a person and finished without one.
     *
     * <p>The binary runs on one thread and the estate answers it on another, which is how an
     * endpoint hook actually behaves: the tool asks, something outside it does the work, and the
     * tool waits for that something to finish before it runs the verify step.
     */
    @Test
    @Order(2)
    @Timeout(1200)
    @DisplayName("moves the scoped workload once nothing unlisted is attached to it")
    void theCanary() throws Exception {
        CompletableFuture<Binary.Result> running = CompletableFuture.supplyAsync(() ->
                Binary.run("apply", "-f", deployment.toString(), "--yes"));

        eventually("the tool asks the estate to switch the endpoint", () -> Files.exists(switching));
        notifierOnGreen = keepingUp(GREEN, NOTIFIER, MOVES);
        eventually("green reports the service that followed the endpoint",
                () -> Lab.consumingUsers(GREEN).contains(NOTIFIER));
        Files.writeString(switched, "moved\n");

        Binary.Result done = running.get(15, TimeUnit.MINUTES);

        assertThat(done.status()).isZero();
        // The default canary list, which is the default blue/green list with a narrower selector
        // and a narrower drain.
        assertThat(done.all()).contains("backup", "topology", "announce-drain", "pause-producers",
                "drain-consumers", "drain-messages", "policies", "switch-endpoint", "verify");
        // One connection closed, and it is the named service's. The other workload's consumer was
        // connected, consuming and holding an unsettled delivery the whole time.
        assertThat(done.all()).contains("closed 1");
    }

    @Test
    @Order(3)
    @Timeout(180)
    @DisplayName("leaves the scoped queues empty on blue and holding the backlog on green")
    void theScopedWorkloadMoved() {
        assertThat(depth(BLUE, MOVES)).isZero();
        assertThat(depth(BLUE, MOVES_DLQ)).isZero();
        assertThat(depth(GREEN, MOVES_DLQ)).isEqualTo(DEAD_LETTERS);
    }

    @Test
    @Order(4)
    @Timeout(180)
    @DisplayName("leaves the unmoved workload genuinely untouched")
    void theOtherWorkloadDidNotMove() {
        // Still on blue, still the same depth, still with its consumer attached and still holding
        // the one delivery it never settled. A canary that closed this connection or drained this
        // queue would have turned one service's migration into everybody's.
        assertThat(depth(BLUE, STAYS)).isEqualTo(ORDERS);
        assertThat(depth(BLUE, STAYS_AUDIT)).isEqualTo(AUDIT);
        assertThat(unacked(BLUE, STAYS)).isEqualTo(1);
        assertThat(Lab.consumingUsers(BLUE)).contains(ORDERS_SERVICE);
        assertThat(depth(GREEN, STAYS)).isZero();
        assertThat(depth(GREEN, STAYS_AUDIT)).isZero();
    }

    // ---------------------------------------------------------------- the estate's own bits

    private static Map<String, Long> depths() {
        Map<String, Long> depths = new HashMap<>();
        for (String queue : new String[] {MOVES, MOVES_DLQ, STAYS, STAYS_AUDIT}) {
            depths.put(queue, depth(BLUE, queue));
        }
        return depths;
    }

    /** A consumer that settles what it is given, so the queue it is on stays empty. */
    private static Connection keepingUp(RabbitMQContainer container, String service, String queue) {
        Connection connection = Lab.connect(container, service, SERVICE_PASSWORD, service);
        try {
            Channel channel = connection.createChannel();
            channel.basicConsume(queue, true, new DefaultConsumer(channel));
            return connection;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    /** A consumer that takes one delivery and never settles it. */
    private static Connection slowConsumer(RabbitMQContainer container, String service,
                                           String queue) {
        Connection connection = Lab.connect(container, service, SERVICE_PASSWORD, service);
        try {
            Channel channel = connection.createChannel();
            channel.basicQos(1);
            channel.basicConsume(queue, false, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    // Deliberately nothing. The delivery is held, which is what makes the
                    // out-of-scope guard question real.
                }
            });
            return connection;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    /**
     * The command the estate supplied, which in a real estate edits a mesh route.
     *
     * <p>It announces that it has been asked and then waits to be told the service has arrived.
     * Written to disk and made executable, because that is what the file says it is: a path the tool
     * runs, with the target substituted into its arguments.
     */
    private static Path hook() {
        try {
            Path script = work.resolve("switch-endpoint.sh");
            Files.writeString(script, """
                    #!/bin/sh
                    # Stands in for whatever moves one service's route. It says it was asked, waits
                    # for the service to actually be there, and only then reports success -- which
                    # is the behaviour an endpoint hook is supposed to have and the reason it has a
                    # timeout rather than being fire and forget.
                    #
                    # $1 is the cluster to point at and $2 is the service to point, which is the
                    # whole difference between a canary and a cutover.
                    echo "switching $2 to $1"
                    touch '%s'
                    while [ ! -f '%s' ]; do sleep 0.2; done
                    echo "switched"
                    """.formatted(switching, switched), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            return script;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    // ---------------------------------------------------------------- the file

    private static String yaml(Path hook) {
        return """
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
                  # The service is in the arguments as well as the target, and
                  # the tool warns when it is not: a canary needs PER-SERVICE
                  # routing, so a hook told only which cluster to point at is a
                  # hook that moves the whole estate. This file is meant to be
                  # one somebody could copy.
                  kind: hook
                  run: %s
                  args: ["{{target}}", "%s"]
                  timeout: 5m

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
                """.formatted(management(BLUE), amqp(BLUE, "blue"), BLUE.getAdminUsername(),
                BLUE.getAdminPassword(), management(GREEN), amqp(GREEN, "green"),
                GREEN.getAdminUsername(), GREEN.getAdminPassword(), hook, NOTIFIER,
                work.resolve("backups").resolve("{{name}}-{{timestamp}}.json"),
                MOVES, MOVES_DLQ, NOTIFIER);
    }
}
