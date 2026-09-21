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
package org.acemq.infra.execute.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.acemq.infra.config.TopologyPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Splitting a definitions document, which is the step ordering that keeps a backlog alive.
 *
 * <p>Tested against a document written out here rather than one read from a broker, because the
 * behaviours that matter are all arithmetic: which sections a half of the copy carries, which
 * entries belong to the virtual host in scope, and what is taken out of a backup before it is
 * written to somebody's laptop.
 */
class RabbitTopologyTest {

    private static final Map<String, Object> DOCUMENT = Map.of(
            "rabbit_version", "4.0.5",
            "users", List.of(
                    Map.of("name", "admin", "password_hash", "S3CR3THASH",
                            "hashing_algorithm", "rabbit_password_hashing_sha256",
                            "tags", "administrator")),
            "vhosts", List.of(Map.of("name", "/orders")),
            "permissions", List.of(Map.of("user", "admin", "vhost", "/orders",
                    "configure", ".*", "write", ".*", "read", ".*")),
            "queues", List.of(
                    Map.of("name", "orders.new", "vhost", "/orders", "durable", true),
                    Map.of("name", "audit.events", "vhost", "/audit", "durable", true)),
            "exchanges", List.of(Map.of("name", "orders", "vhost", "/orders", "type", "topic")),
            "bindings", List.of(Map.of("source", "orders", "vhost", "/orders",
                    "destination", "orders.new")),
            "policies", List.of(Map.of("name", "short-ttl", "vhost", "/orders",
                    "pattern", "^orders\\.notifications$", "apply-to", "queues",
                    "definition", Map.of("message-ttl", 10000), "priority", 1)),
            "parameters", List.of(Map.of("name", "upstream", "vhost", "/orders",
                    "component", "federation-upstream", "value", Map.of())));

    private static RabbitTopology topology() {
        return RabbitTopology.of("blue", "/orders", DOCUMENT, List.of());
    }

    @Nested
    @DisplayName("narrowing to a virtual host")
    class Narrowing {

        @Test
        void keepsOnlyTheEntriesThatBelongToIt() {
            assertThat(topology().count(TopologyPart.QUEUES)).isEqualTo(1);
            assertThat(topology().documentFor(List.of(TopologyPart.QUEUES)))
                    .contains("orders.new").doesNotContain("audit.events");
        }

        @Test
        void keepsTheVhostsSectionWhole() {
            // A definitions import creates a virtual host that is missing, which is the difference
            // between a copy that works on a fresh cluster and one that needs somebody to have run
            // rabbitmqctl first.
            assertThat(topology().documentFor(List.of(TopologyPart.VHOSTS))).contains("/orders");
        }
    }

    @Nested
    @DisplayName("splitting the copy")
    class Splitting {

        @Test
        void carriesOnlyTheSectionsThisHalfApplies() {
            String half = topology().documentFor(List.of(TopologyPart.EXCHANGES,
                    TopologyPart.QUEUES, TopologyPart.BINDINGS));

            // The whole reason the step list splits the copy around the drain: a message-ttl that
            // lands with the shape is live on an empty cluster long before the backlog arrives,
            // and a forty-minute backlog drained into a ten-minute TTL is discarded on arrival.
            assertThat(half).doesNotContain("short-ttl").doesNotContain("message-ttl");
            assertThat(half).contains("orders.new");
        }

        @Test
        void carriesThePoliciesWhenTheOtherHalfAsksForThem() {
            assertThat(topology().documentFor(List.of(TopologyPart.POLICIES)))
                    .contains("short-ttl").contains("message-ttl");
        }

        @Test
        void keepsTheBrokerVersionInEveryHalf() {
            assertThat(topology().documentFor(List.of(TopologyPart.QUEUES)))
                    .contains("rabbit_version");
        }

        @Test
        void hasNoSectionForOperatorPolicies() {
            // Found by trying to write this: /api/definitions carries `policies` and nothing of
            // that kind for operator policies, which live behind their own endpoint and have to be
            // applied one at a time.
            RabbitTopology with = RabbitTopology.of("blue", "/orders", DOCUMENT, List.of());
            assertThat(with.documentFor(List.of(TopologyPart.OPERATOR_POLICIES)))
                    .doesNotContain("operator");
        }
    }

    @Nested
    @DisplayName("the backup")
    class Backup {

        @Test
        void takesThePasswordDataOutByDefault(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("orders.json");
            topology().writeTo(file, true);

            // A hash is enough to stand up a broker the real passwords authenticate against, and a
            // backup goes on somebody's laptop. The trade is that this file cannot restore a
            // cluster on its own, which is said here rather than discovered during a restore.
            assertThat(Files.readString(file))
                    .doesNotContain("S3CR3THASH").contains("\"admin\"");
        }

        @Test
        void keepsItWhenAskedTo(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("orders.json");
            topology().writeTo(file, false);

            assertThat(Files.readString(file)).contains("S3CR3THASH");
        }

        @Test
        void isNotReadableByAnybodyElseWhenItIsACredential(@TempDir Path directory)
                throws IOException {
            Path file = directory.resolve("orders.json");
            topology().writeTo(file, false);

            assertThat(Files.getPosixFilePermissions(file))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void describesItselfInCountsAndNothingThatIsASecret() {
        assertThat(topology().describe())
                .contains("1 queues").contains("1 exchanges").doesNotContain("S3CR3THASH");
    }
}
