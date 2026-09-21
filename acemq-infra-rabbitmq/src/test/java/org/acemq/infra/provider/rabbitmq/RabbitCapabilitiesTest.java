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

import java.util.List;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ProbedCluster;
import org.acemq.infra.provider.rabbitmq.ManagementEndpoint.Presence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a version, a plugin and a tag add up to.
 *
 * <p>No container in this file, deliberately. docs/broker-agnostic.md's claim is that a
 * capability is missing for three different reasons with three different fixes, and the estates
 * that make that claim worth anything are the awkward ones: a monitoring-only account on a cluster
 * where every plugin is enabled, an administrator on a broker too old for half of what it is being
 * asked for, a cluster with federation and no shovel. Arranging each of those as a container is a
 * morning; arranging them as a record is a line, and a suite that has to configure brokers covers
 * two of them and skips the rest.
 */
class RabbitCapabilitiesTest {

    private static final List<String> ADMIN = List.of("administrator");
    private static final List<String> MONITORING = List.of("monitoring");

    private static ProbedCluster probe(BrokerFacts facts) {
        return RabbitCapabilities.of(facts, ProbedCluster.named("blue").version(facts.version()))
                .build();
    }

    private static BrokerFacts healthy() {
        return new BrokerFacts("4.0.5", ADMIN, Presence.PRESENT, Presence.PRESENT, true, true,
                true);
    }

    @Test
    @DisplayName("a current broker with every plugin and an administrator can do everything")
    void everything() {
        ProbedCluster cluster = probe(healthy());
        assertThat(cluster.present()).containsExactlyInAnyOrder(Capability.values());
    }

    @Test
    @DisplayName("every verdict carries what was observed, present or absent")
    void everyVerdictExplainsItself() {
        ProbedCluster cluster = probe(healthy());
        assertThat(cluster.capabilities()).allSatisfy((capability, verdict) ->
                assertThat(verdict.because()).as(capability.name()).isNotBlank());
    }

    @Nested
    @DisplayName("a plugin that is not enabled")
    class Plugins {

        @Test
        @DisplayName("takes the drain with it, and says which plugin")
        void shovel() {
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5", ADMIN, Presence.ABSENT,
                    Presence.PRESENT, true, true, true));
            assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isFalse();
            assertThat(cluster.whyNot(Capability.DRAIN_BY_SHOVEL))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("rabbitmq_shovel")
                            .contains("not enabled"));
            assertThat(cluster.can(Capability.MIRROR_BY_FEDERATION)).isTrue();
        }

        @Test
        @DisplayName("takes the mirror with it, independently")
        void federation() {
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5", ADMIN, Presence.PRESENT,
                    Presence.ABSENT, true, true, true));
            assertThat(cluster.can(Capability.MIRROR_BY_FEDERATION)).isFalse();
            assertThat(cluster.whyNot(Capability.MIRROR_BY_FEDERATION))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("rabbitmq_federation"));
            assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isTrue();
        }

        @Test
        @DisplayName("shows up in the facilities the probe summary prints")
        void facilities() {
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5", ADMIN, Presence.PRESENT,
                    Presence.ABSENT, false, true, true));
            assertThat(cluster.facilityMarks()).isEqualTo("shovel✓ federation✗ streams✗");
        }

        @Test
        @DisplayName("is not concluded from credentials that could not look")
        void forbidden() {
            // A 403 is not a missing plugin, and reporting it as one sends somebody to enable a
            // plugin that is already enabled.
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5", MONITORING, Presence.FORBIDDEN,
                    Presence.FORBIDDEN, true, true, true));
            assertThat(cluster.whyNot(Capability.DRAIN_BY_SHOVEL))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("may not read")
                            .contains("could not be established"));
        }
    }

    @Nested
    @DisplayName("a version")
    class Versions {

        @Test
        @DisplayName("before 3.7 has no operator policies, whatever the credentials are")
        void operatorPolicies() {
            ProbedCluster cluster = probe(new BrokerFacts("3.6.16", ADMIN, Presence.PRESENT,
                    Presence.PRESENT, false, true, true));
            assertThat(cluster.can(Capability.OPERATOR_POLICY)).isFalse();
            assertThat(cluster.whyNot(Capability.OPERATOR_POLICY))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("arrived in 3.7"));
        }

        @Test
        @DisplayName("before 3.9 has no streams")
        void streams() {
            ProbedCluster cluster = probe(new BrokerFacts("3.8.35", ADMIN, Presence.PRESENT,
                    Presence.PRESENT, false, true, true));
            assertThat(cluster.whyNot(Capability.STREAM_OFFSET_READ))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("arrived in 3.9"));
        }

        @Test
        @DisplayName("at 3.9 with the feature flag off still has no streams, for a different reason")
        void streamFlagOff() {
            ProbedCluster cluster = probe(new BrokerFacts("3.13.7", ADMIN, Presence.PRESENT,
                    Presence.PRESENT, false, true, true));
            assertThat(cluster.whyNot(Capability.STREAM_OFFSET_READ))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("stream_queue"));
        }

        @Test
        @DisplayName("says a stream offset is a read and can never be a write")
        void offsetsAreReadOnly() {
            assertThat(probe(healthy()).capabilities().get(Capability.STREAM_OFFSET_READ).because())
                    .contains("cannot be written into another cluster's log");
        }

        @Test
        @DisplayName("is read past its suffixes, because brokers ship 4.1.0-rc.1 and 3.12+dfsg1")
        void awkwardVersions() {
            assertThat(new BrokerFacts("4.1.0-rc.1", ADMIN, Presence.PRESENT, Presence.PRESENT,
                    true, true, true).atLeast(3, 9)).isTrue();
            assertThat(new BrokerFacts("3.12.14+dfsg1", ADMIN, Presence.PRESENT, Presence.PRESENT,
                    true, true, true).atLeast(3, 9)).isTrue();
            assertThat(new BrokerFacts("3.8.35", ADMIN, Presence.PRESENT, Presence.PRESENT, true,
                    true, true).atLeast(3, 9)).isFalse();
        }

        @Test
        @DisplayName("that cannot be read at all is treated as too old rather than as new enough")
        void unknownVersion() {
            // The direction that refuses a plan, rather than the direction that promises a
            // capability nobody established.
            BrokerFacts unknown = new BrokerFacts("unknown", ADMIN, Presence.PRESENT,
                    Presence.PRESENT, true, true, true);
            assertThat(unknown.atLeast(3, 7)).isFalse();
            assertThat(probe(unknown).can(Capability.OPERATOR_POLICY)).isFalse();
        }
    }

    @Nested
    @DisplayName("a monitoring-only account")
    class Permissions {

        private final BrokerFacts monitoring = new BrokerFacts("4.0.5", MONITORING,
                Presence.PRESENT, Presence.PRESENT, true, false, true);

        @Test
        @DisplayName("can watch the connection it is about to fail to close")
        void cannotClose() {
            ProbedCluster cluster = probe(monitoring);
            assertThat(cluster.can(Capability.CONSUMER_INSPECT)).isTrue();
            assertThat(cluster.can(Capability.CONNECTION_CLOSE)).isFalse();
            assertThat(cluster.whyNot(Capability.CONNECTION_CLOSE))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("monitoring").contains("needs administrator"));
        }

        @Test
        @DisplayName("cannot import a topology onto the target")
        void cannotImport() {
            assertThat(probe(monitoring).can(Capability.TOPOLOGY_IMPORT_MERGE)).isFalse();
        }

        @Test
        @DisplayName("cannot declare the shovel, even where the plugin is enabled")
        void cannotDeclareAShovel() {
            // The combination that reads as healthy right up until the drain: every plugin on,
            // every endpoint answering, and nothing may be declared on any of them.
            ProbedCluster cluster = probe(monitoring);
            assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isFalse();
            assertThat(cluster.whyNot(Capability.DRAIN_BY_SHOVEL))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("are enabled")
                            .contains("needs administrator or policymaker"));
        }

        @Test
        @DisplayName("is allowed to declare one when it holds the policymaker tag")
        void policymakerMayDeclare() {
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5",
                    List.of("monitoring", "policymaker"), Presence.PRESENT, Presence.PRESENT, true,
                    true, true));
            assertThat(cluster.can(Capability.DRAIN_BY_SHOVEL)).isTrue();
            assertThat(cluster.can(Capability.MIRROR_BY_FEDERATION)).isTrue();
        }

        @Test
        @DisplayName("cannot export the definitions, and the reason names the tag it has")
        void cannotExport() {
            assertThat(probe(monitoring).whyNot(Capability.TOPOLOGY_EXPORT))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("monitoring"));
        }

        @Test
        @DisplayName("with no tags at all says so rather than printing an empty list")
        void noTags() {
            ProbedCluster cluster = probe(new BrokerFacts("4.0.5", List.of(), Presence.PRESENT,
                    Presence.PRESENT, true, false, false));
            assertThat(cluster.whyNot(Capability.CONNECTION_CLOSE))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("no tags at all"));
            assertThat(cluster.can(Capability.CONSUMER_INSPECT)).isFalse();
        }
    }
}
