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

import java.nio.file.Files;
import java.nio.file.Path;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFileParser;
import org.acemq.infra.config.Environment;
import org.acemq.infra.plan.Plan;
import org.acemq.infra.plan.Planner;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ClusterAccess;
import org.acemq.infra.provider.ProbedCluster;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A cluster that cannot do it, and a plan that says so before anything happens.
 *
 * <p>The stock management image, untouched. It ships the shovel and federation plugins and does
 * not enable them, which is also true of plenty of production clusters — so this is not a
 * contrived estate, it is the default one.
 *
 * <p>This is the claim docs/broker-agnostic.md makes for the capability model on a single broker,
 * end to end: the plan turns "the cutover failed halfway" into "blue has rabbitmq_shovel disabled,
 * so step drain-messages cannot run", and it does it at second zero rather than at step six with
 * half an estate moved.
 */
@Testcontainers
@DisplayName("a cluster with the plugins not enabled")
class MissingPluginsIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String url;

    @BeforeAll
    static void setUp() {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);
    }

    private static ProbedCluster probe(String name) {
        return new RabbitProbe().probe(ClusterAccess.to(name, url, "/",
                BROKER.getAdminUsername(), BROKER.getAdminPassword()));
    }

    @Test
    @Timeout(180)
    @DisplayName("has no drain, and the reason names the plugin to enable")
    void noShovel() {
        ProbedCluster cluster = probe("blue");
        assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isFalse();
        assertThat(cluster.whyNot(Capability.DRAIN_BY_SHOVEL))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("rabbitmq_shovel")
                        .contains("not enabled"));
        assertThat(cluster.facilityMarks()).startsWith("shovel✗");
    }

    @Test
    @Timeout(180)
    @DisplayName("has no mirror either, and the two are established separately")
    void noFederation() {
        ProbedCluster cluster = probe("blue");
        assertThat(cluster.can(Capability.MIRROR_BY_FEDERATION)).isFalse();
        assertThat(cluster.whyNot(Capability.MIRROR_BY_FEDERATION))
                .hasValueSatisfying(reason -> assertThat(reason).contains("rabbitmq_federation"));
    }

    @Test
    @Timeout(180)
    @DisplayName("still exports its topology and still sees its consumers")
    void everythingElseWorks() {
        ProbedCluster cluster = probe("blue");
        assertThat(cluster.can(Capability.TOPOLOGY_EXPORT)).isTrue();
        assertThat(cluster.can(Capability.CONSUMER_INSPECT)).isTrue();
        assertThat(cluster.can(Capability.CONNECTION_CLOSE)).isTrue();
    }

    @Test
    @Timeout(180)
    @DisplayName("produces a plan that refuses, naming the step, the cluster and the plugin")
    void thePlanRefuses(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cutover.yaml");
        Files.writeString(file, """
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: cutover
                provider: rabbitmq
                clusters:
                  blue:
                    management: %s
                    username: %s
                    password: %s
                  green:
                    management: %s
                    username: %s
                    password: %s
                endpoint:
                  kind: external
                  description: switched by hand
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                """.formatted(url, BROKER.getAdminUsername(), BROKER.getAdminPassword(),
                url, BROKER.getAdminUsername(), BROKER.getAdminPassword()));

        DeploymentFile deployment = DeploymentFileParser.parse(Files.readString(file),
                file.toString(), Environment.system());
        // Probed twice under the two names the file uses. One container is enough to make the
        // point: the plan's refusal is about what a named cluster can do, and both of these can
        // do the same nothing.
        Plan plan = Planner.plan(deployment, probe("blue"), probe("green"));

        assertThat(plan.ok()).isFalse();
        assertThat(plan.refusals()).anyMatch(refusal -> refusal.contains("drain-messages")
                && refusal.contains("DRAIN_BY_SHOVEL")
                && refusal.contains("rabbitmq_shovel"));
        assertThat(plan.render())
                .contains("nothing was written, and nothing would be: this plan is refused.");
    }
}
