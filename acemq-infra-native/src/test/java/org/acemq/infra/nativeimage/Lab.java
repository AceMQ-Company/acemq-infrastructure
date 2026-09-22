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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;

import org.acemq.rabbitmq.admin.ConsumerInfo;
import org.acemq.rabbitmq.admin.PolicyInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The estate the binary is pointed at, and the ways of reading it back.
 *
 * <p>The same lab {@code scripts/blue-green-lab.sh} builds and the same one
 * {@code acemq-infra-execute}'s integration tests build: two <em>separate</em> single-node clusters
 * sharing a Docker network and nothing else. Two nodes of one cluster would let a whole class of
 * mistake pass that production would not, and the shared network exists for one reason — a shovel
 * runs inside a broker and has to be able to dial the other one.
 *
 * <p>What is deliberately not here is anything that reads the estate through the tool. The
 * assertions in this module are made with a management client of the test's own, against the two
 * brokers, after the binary has exited. A binary that reported a successful drain and left the
 * messages where they were would pass a suite that believed its narration.
 */
final class Lab {

    /** The same image the rest of the suite uses, so a broker change lands on all of it at once. */
    private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:4-management");

    private Lab() {
    }

    /**
     * One of the two clusters.
     *
     * @param network the one thing the pair shares
     * @param alias the name the other broker dials it by
     * @return the container, not yet started
     */
    static RabbitMQContainer broker(Network network, String alias) {
        return new RabbitMQContainer(IMAGE)
                // The image ships the plugins and does not enable them. A cutover cannot happen
                // without both, which is the capability question docs/broker-agnostic.md is about,
                // and a probe against a broker without them is a different test.
                .withPluginsEnabled("rabbitmq_shovel", "rabbitmq_shovel_management",
                        "rabbitmq_federation", "rabbitmq_federation_management")
                .withNetwork(network)
                .withNetworkAliases(alias);
    }

    // ---------------------------------------------------------------- reaching a cluster

    /** Where this JVM, and the binary it starts, reach the management API. */
    static String management(RabbitMQContainer container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(15672);
    }

    /**
     * A cluster's AMQP URI <em>as the other broker will dial it</em>.
     *
     * <p>The container alias on the shared network, not the mapped port on this machine. A shovel
     * runs inside a broker; the address this JVM uses to reach a container is not an address the
     * other container has ever heard of, and getting this wrong is a shovel that never connects.
     */
    static String amqp(RabbitMQContainer any, String alias) {
        return "amqp://" + any.getAdminUsername() + ":" + any.getAdminPassword() + "@" + alias
                + ":5672";
    }

    static RabbitAdmin admin(RabbitMQContainer container) {
        return RabbitAdmin.connect(management(container), container.getAdminUsername(),
                container.getAdminPassword()).forVhost("/");
    }

    // ---------------------------------------------------------------- reading it back

    static long depth(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messages).orElse(0L);
    }

    static long unacked(RabbitMQContainer container, String queue) {
        return queue(container, queue).map(QueueInfo::messagesUnacknowledged).orElse(0L);
    }

    static Optional<QueueInfo> queue(RabbitMQContainer container, String name) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().filter(queue -> queue.name().equals(name)).findFirst();
        }
    }

    static List<String> names(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.queues().stream().map(QueueInfo::name).toList();
        }
    }

    static List<String> policyNames(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.policies().stream().map(PolicyInfo::name).toList();
        }
    }

    /** Which users are consuming, which is the identity a canary's {@code services:} matches. */
    static List<String> consumingUsers(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.channels().stream()
                    .filter(channel -> channel.consumerCount() > 0)
                    .map(channel -> channel.user())
                    .distinct()
                    .toList();
        }
    }

    static List<ConsumerInfo> consumers(RabbitMQContainer container) {
        try (RabbitAdmin admin = admin(container)) {
            return admin.consumers();
        }
    }

    // ---------------------------------------------------------------- putting things on it

    static Connection connect(RabbitMQContainer container, String user, String password,
                              String name) {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(container.getHost());
            factory.setPort(container.getAmqpPort());
            factory.setUsername(user);
            factory.setPassword(password);
            return factory.newConnection(name);
        } catch (Exception unreachable) {
            throw new IllegalStateException("could not connect to " + container.getNetworkAliases(),
                    unreachable);
        }
    }

    static void publish(Channel channel, String exchange, String key, String prefix, int count) {
        try {
            for (int number = 0; number < count; number++) {
                channel.basicPublish(exchange, key, MessageProperties.PERSISTENT_TEXT_PLAIN,
                        (prefix + number).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    // ---------------------------------------------------------------- the file the binary reads

    /**
     * Writes a deployment file where the binary can read it.
     *
     * <p>Through a real file on disk, because that is the interface. docs/shape.md's whole argument
     * for this shape is that the configuration file <em>is</em> the interface and the CLI is a thin
     * reader of it, so a test that handed the tool a parsed model would be testing something else.
     *
     * @param directory where to put it
     * @param name what to call it
     * @param yaml its contents
     * @return the path to pass to {@code -f}
     */
    static Path file(Path directory, String name, String yaml) {
        try {
            Path written = directory.resolve(name);
            Files.writeString(written, yaml, StandardCharsets.UTF_8);
            return written;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    // ---------------------------------------------------------------- waiting

    /**
     * Waits for something the statistics database has to catch up with.
     *
     * <p>The management API's depths and counts refresh on {@code collect_statistics_interval}
     * rather than on every event, so a test that asserted immediately would be asserting against
     * numbers from before it did anything. The guards inside the tool are built around the same
     * hazard; it applies to a test's own assertions exactly as much.
     *
     * @param what the thing being waited for, for the message when it never happens
     * @param condition how to tell
     */
    static void eventually(String what, BooleanSupplier condition) {
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
