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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.acemq.infra.Fixtures;
import org.acemq.infra.config.Action;
import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.MirrorSpec;
import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.Operation;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;
import org.acemq.infra.yaml.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The default step list, and specifically its order.
 *
 * <p>docs/configuration.md replaced three booleans with an explicit list because no arrangement of
 * booleans can express a topology import that happens in two halves with a drain between them.
 * That makes the order of this list the thing the format exists to protect, so it is asserted
 * position by position rather than as a set.
 */
class DefaultStepsTest {

    private static List<String> ids(List<Step> steps) {
        return steps.stream().map(Step::describeId).toList();
    }

    private static int at(List<Step> steps, String id) {
        return ids(steps).indexOf(id);
    }

    private static List<Step> blueGreen() {
        return DefaultSteps.of(Operation.BLUE_GREEN, "blue", "green", Optional.empty(),
                Optional.empty());
    }

    @Nested
    @DisplayName("blueGreen")
    class BlueGreen {

        @Test
        @DisplayName("is the sequence docs/blue-green.md numbers, less the probe summary")
        void order() {
            assertThat(ids(blueGreen())).containsExactly("probe", "topology", "announce-drain",
                    "pause-producers", "drain-consumers", "drain-messages", "policies",
                    "switch-endpoint", "verify");
        }

        @Test
        @DisplayName("splits the topology copy around the drain")
        void theSplit() {
            List<Step> steps = blueGreen();
            assertThat(at(steps, "topology"))
                    .isLessThan(at(steps, "drain-messages"));
            assertThat(at(steps, "drain-messages"))
                    .isLessThan(at(steps, "policies"));
        }

        @Test
        @DisplayName("keeps the retention policies out of the first half")
        void firstHalfExcludesRetention() {
            Action.CopyTopology copy = blueGreen().get(at(blueGreen(), "topology"))
                    .find(Action.CopyTopology.class).orElseThrow();
            assertThat(copy.exclude())
                    .containsExactly(TopologyPart.POLICIES, TopologyPart.OPERATOR_POLICIES);
            assertThat(copy.include()).doesNotContain(TopologyPart.POLICIES,
                    TopologyPart.OPERATOR_POLICIES);
        }

        @Test
        @DisplayName("puts the retention policies back in the second half")
        void secondHalfAppliesRetention() {
            Action.CopyTopology copy = blueGreen().get(at(blueGreen(), "policies"))
                    .find(Action.CopyTopology.class).orElseThrow();
            assertThat(copy.include())
                    .containsExactly(TopologyPart.POLICIES, TopologyPart.OPERATOR_POLICIES);
            assertThat(copy.exclude()).isEmpty();
        }

        @Test
        @DisplayName("stops the producers before it closes the consumers")
        void producersFirst() {
            List<Step> steps = blueGreen();
            assertThat(at(steps, "pause-producers")).isLessThan(at(steps, "drain-consumers"));
            assertThat(at(steps, "drain-consumers")).isLessThan(at(steps, "drain-messages"));
        }

        @Test
        @DisplayName("waits for the source to settle before the shovel runs")
        void settlesBeforeDraining() {
            List<Step> steps = blueGreen();
            List<Step> before = steps.subList(0, at(steps, "drain-messages"));
            assertThat(before).anyMatch(step -> step.waitFor()
                    .filter(WaitFor::settlesTheSource).isPresent());
        }

        @Test
        @DisplayName("announces immediately before the first thing that waits")
        void announceIsLate() {
            List<Step> steps = blueGreen();
            assertThat(at(steps, "announce-drain")).isGreaterThan(at(steps, "topology"));
            assertThat(at(steps, "announce-drain")).isEqualTo(at(steps, "pause-producers") - 1);
        }

        @Test
        @DisplayName("verifies after the endpoint moves, which is the point of verifying")
        void verifyIsLast() {
            List<Step> steps = blueGreen();
            assertThat(at(steps, "verify")).isEqualTo(steps.size() - 1);
            assertThat(at(steps, "switch-endpoint")).isEqualTo(at(steps, "verify") - 1);
        }

        @Test
        @DisplayName("asserts the capabilities a cutover needs before anything happens")
        void probeRequires() {
            Action.Requires requires = blueGreen().get(0).find(Action.Requires.class).orElseThrow();
            assertThat(requires.capabilities()).containsExactly(
                    org.acemq.infra.provider.Capability.TOPOLOGY_EXPORT,
                    org.acemq.infra.provider.Capability.TOPOLOGY_IMPORT_MERGE,
                    org.acemq.infra.provider.Capability.DRAIN_BY_SHOVEL,
                    org.acemq.infra.provider.Capability.CONNECTION_CLOSE,
                    org.acemq.infra.provider.Capability.CONSUMER_INSPECT);
        }

        @Test
        @DisplayName("gives every waiting step a timeout and a decision about it")
        void everyGuardDecides() {
            for (Step step : blueGreen()) {
                step.waitFor().ifPresent(guard -> {
                    assertThat(guard.timeout()).as(step.describeId() + " timeout").isPresent();
                    assertThat(guard.onTimeout()).as(step.describeId() + " onTimeout").isPresent();
                });
            }
        }

        @Test
        @DisplayName("aborts rather than continues once something destructive has happened")
        void abortsAfterTheDrain() {
            List<Step> steps = blueGreen();
            WaitFor drain = steps.get(at(steps, "drain-messages")).waitFor().orElseThrow();
            assertThat(drain.onTimeout()).contains(OnTimeout.ABORT);
        }

        @Test
        @DisplayName("gives every step an id, because everything else refers to steps by id")
        void everyStepHasAnId() {
            assertThat(blueGreen()).allSatisfy(step -> assertThat(step.id()).isPresent());
        }
    }

    @Nested
    @DisplayName("canary")
    class Canary {

        private List<Step> canary() {
            Deployment.Scope scope = new Deployment.Scope(Optional.of("/orders"),
                    List.of("orders.notifications", "orders.notifications.dlq"),
                    List.of("notification-service"), List.of(), Location.unknown("test"));
            return DefaultSteps.of(Operation.CANARY, "blue", "green", Optional.of(scope),
                    Optional.empty());
        }

        @Test
        @DisplayName("is the same sequence, because it is the same machinery at a smaller scope")
        void sameOrder() {
            assertThat(ids(canary())).isEqualTo(ids(blueGreen()));
        }

        @Test
        @DisplayName("drains only the scoped queues")
        void drainIsScoped() {
            Action.Drain drain = canary().get(at(canary(), "drain-messages"))
                    .find(Action.Drain.class).orElseThrow();
            assertThat(drain.queues())
                    .containsExactly("orders.notifications", "orders.notifications.dlq");
        }

        @Test
        @DisplayName("closes only the scoped services' connections")
        void closeIsScoped() {
            Action.CloseConnections close = canary().get(at(canary(), "drain-consumers"))
                    .find(Action.CloseConnections.class).orElseThrow();
            assertThat(close.select().orElseThrow().users())
                    .containsExactly("notification-service");
        }

        @Test
        @DisplayName("copies only the scoped vhost")
        void topologyIsScoped() {
            Action.CopyTopology copy = canary().get(at(canary(), "topology"))
                    .find(Action.CopyTopology.class).orElseThrow();
            assertThat(copy.vhosts()).containsExactly("/orders");
        }

        @Test
        @DisplayName("closes every consuming connection when nothing narrows it")
        void unscopedClosesEverything() {
            Action.CloseConnections close = blueGreen().get(at(blueGreen(), "drain-consumers"))
                    .find(Action.CloseConnections.class).orElseThrow();
            assertThat(close.select().orElseThrow().users()).isEmpty();
            assertThat(close.select().orElseThrow().role()).contains("consumer");
        }
    }

    @Nested
    @DisplayName("mirror")
    class Mirror {

        private List<Step> mirror() {
            MirrorSpec spec = new MirrorSpec(List.of("orders"), List.of(), Optional.empty(),
                    Location.unknown("test"));
            return DefaultSteps.of(Operation.MIRROR, "blue", "green", Optional.empty(),
                    Optional.of(spec));
        }

        @Test
        @DisplayName("probes, copies the shape, federates, and checks somebody is listening")
        void order() {
            assertThat(ids(mirror())).containsExactly("probe", "topology", "mirror", "verify");
        }

        @Test
        @DisplayName("never drains, because a mirror that moves messages is not a mirror")
        void neverDrains() {
            assertThat(mirror()).noneMatch(step -> step.has(Action.Drain.class));
        }

        @Test
        @DisplayName("federates the exchanges the file named and no queues at all")
        void federatesExchanges() {
            Action.Mirror federation = mirror().get(2).find(Action.Mirror.class).orElseThrow();
            assertThat(federation.exchanges()).containsExactly("orders");
            assertThat(federation.queues()).isEmpty();
        }

        @Test
        @DisplayName("copies the policies with the shape, because there is no backlog to protect")
        void copiesEverything() {
            Action.CopyTopology copy = mirror().get(1).find(Action.CopyTopology.class).orElseThrow();
            assertThat(copy.exclude()).isEmpty();
        }

        @Test
        @DisplayName("does not ask for the shovel it will never declare")
        void requiresFederationNotShovel() {
            Action.Requires requires = mirror().get(0).find(Action.Requires.class).orElseThrow();
            assertThat(requires.capabilities())
                    .contains(org.acemq.infra.provider.Capability.MIRROR_BY_FEDERATION)
                    .doesNotContain(org.acemq.infra.provider.Capability.DRAIN_BY_SHOVEL);
        }

        @Test
        @DisplayName("takes no backup, because nothing leaves the cluster being backed up")
        void noBackup() {
            assertThat(DefaultSteps.backsUpByDefault(Operation.MIRROR)).isFalse();
            assertThat(DefaultSteps.backsUpByDefault(Operation.BLUE_GREEN)).isTrue();
            assertThat(DefaultSteps.backsUpByDefault(Operation.CANARY)).isTrue();
        }
    }

    @Nested
    @DisplayName("the default list is a list a file could have written")
    class RoundTrip {

        /**
         * The property that keeps the default honest. docs/configuration.md promises that the
         * printed list can be pasted into the file and edited, and a default list the validator
         * would reject is a promise that fails the first time somebody takes it up — quietly, in
         * their pull request, with the tool's own output as the thing being refused.
         */
        private ValidationReport validated(String operation, String extra) {
            DeploymentFile parsed = Fixtures.parse(Fixtures.around("""
                    deployment:
                      operation: %s
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    %s""".formatted(operation, extra)));
            Deployment deployment = parsed.deployment().orElseThrow();
            Deployment withSteps = new Deployment(deployment.operation(), deployment.from(),
                    deployment.to(), deployment.semantics(), deployment.scope(),
                    deployment.mirror(), deployment.backup(), deployment.announce(),
                    Optional.of(DefaultSteps.of(deployment)), deployment.unknownKeys(),
                    deployment.location());
            return Validator.validate(new DeploymentFile(parsed.origin(), parsed.apiVersion(),
                    parsed.kind(), parsed.metadata(), parsed.clusters(), parsed.provider(),
                    parsed.providerConfig(), parsed.endpoint(), Optional.of(withSteps),
                    parsed.rollback(), parsed.streams(), parsed.unknownKeys(), parsed.location()));
        }

        @Test
        @DisplayName("the blueGreen default validates")
        void blueGreenValidates() {
            assertThat(Fixtures.errors(validated("blueGreen", ""))).isEmpty();
        }

        @Test
        @DisplayName("the canary default validates")
        void canaryValidates() {
            assertThat(Fixtures.errors(validated("canary", """
                      scope:
                        vhost: /orders
                        queues: [orders.notifications]
                        services: [notification-service]
                    """))).isEmpty();
        }

        @Test
        @DisplayName("the mirror default validates")
        void mirrorValidates() {
            assertThat(Fixtures.errors(validated("mirror", """
                      mirror:
                        exchanges: [orders]
                    """))).isEmpty();
        }
    }
}
