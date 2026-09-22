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
package org.acemq.infra.validate;

import static org.acemq.infra.Fixtures.around;
import static org.acemq.infra.Fixtures.errors;
import static org.acemq.infra.Fixtures.validate;
import static org.acemq.infra.Fixtures.warnings;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rule by rule, with no broker anywhere near it.
 *
 * <p>Arranged the way scripts/lint-deployment.py is arranged, so that the two can be read side by
 * side and a rule that exists in one and not the other is visible. The ordering rules at the
 * bottom are the ones worth reading: they are the only rules here that know anything about
 * brokers, and each of them exists because the arrangement it refuses costs messages.
 */
class ValidatorTest {

    /** The minimum a blue/green needs to produce no errors at all. */
    private static final String CLEAN_BLUE_GREEN = """
            deployment:
              operation: blueGreen
              from: blue
              to: green
              semantics: atLeastOnce
              backup:
                enabled: true
              steps:
                - id: pause-producers
                  waitFor:
                    on: blue
                    publishRate: 0
                    timeout: 2m
                    onTimeout: prompt
                - id: drain-messages
                  drain:
                    from: blue
                    to: green
                    queues: ["orders.*"]
                  waitFor:
                    on: blue
                    depth: 0
                    timeout: 15m
                    onTimeout: abort
            """;

    @Test
    void acceptsACleanFile() {
        ValidationReport report = validate(around(CLEAN_BLUE_GREEN));

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.summary()).isEqualTo("blueGreen, 2 steps, 2 clusters");
    }

    @Nested
    class Header {

        @Test
        void refusesTheWrongApiVersion() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("acemq.org/v1alpha1", "acemq.org/v1"));

            assertThat(errors(report)).contains(
                    "expected 'acemq.org/v1alpha1', found 'acemq.org/v1'");
        }

        @Test
        void refusesTheWrongKind() {
            ValidationReport report =
                    validate(around(CLEAN_BLUE_GREEN).replace("kind: Deployment", "kind: Cutover"));

            assertThat(errors(report)).contains("expected 'Deployment', found 'Cutover'");
        }

        @Test
        void requiresAName() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("  name: test-deployment\n", ""));

            assertThat(report.errors()).anySatisfy(finding -> {
                assertThat(finding.where()).isEqualTo("metadata.name");
                assertThat(finding.message())
                        .isEqualTo("is required — it names the plan, the backup file and every "
                                + "log line");
            });
        }

        @Test
        void refusesAProviderThatDoesNotExist() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("provider: rabbitmq", "provider: kafka"));

            assertThat(errors(report)).contains("'kafka' is not one of [rabbitmq]. RabbitMQ is the "
                    + "only provider that exists (docs/broker-agnostic.md)");
        }

        @Test
        void requiresAProvider() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("provider: rabbitmq\n", ""));

            assertThat(errors(report)).anyMatch(message -> message.startsWith("is required"));
        }

        @Test
        void refusesAnUnknownTopLevelKey() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN) + "extras: {}\n");

            assertThat(report.errors()).anySatisfy(finding -> {
                assertThat(finding.where()).isEqualTo("extras");
                assertThat(finding.message()).isEqualTo("unknown top-level key");
            });
        }
    }

    @Nested
    class Clusters {

        @Test
        void requiresAtLeastOne() {
            ValidationReport report = validate("""
                    apiVersion: acemq.org/v1alpha1
                    kind: Deployment
                    metadata:
                      name: nothing-to-move
                    provider: rabbitmq
                    """);

            assertThat(errors(report)).contains("at least one cluster is required");
        }

        @Test
        void requiresCredentials() {
            ValidationReport report = validate("""
                    apiVersion: acemq.org/v1alpha1
                    kind: Deployment
                    metadata:
                      name: half-a-cluster
                    provider: rabbitmq
                    clusters:
                      blue:
                        amqp: amqps://blue.internal:5671
                    """);

            assertThat(report.errors()).extracting(Finding::where).contains("clusters.blue");
            assertThat(errors(report)).contains("management is required", "username is required",
                    "password is required");
        }

        /**
         * A warning and not an error: a file that only copies topology never needs one. A shovel
         * and a federation link do, because the broker dials the URI itself.
         */
        @Test
        void warnsWhenThereIsNoAmqpUri() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("    amqp: amqps://blue.internal:5671\n", ""));

            assertThat(warnings(report))
                    .contains("no amqp URI; drain and mirror steps need one");
        }

        @Test
        void warnsWhenTlsVerificationIsOff() {
            ValidationReport report = validate("""
                    apiVersion: acemq.org/v1alpha1
                    kind: Deployment
                    metadata:
                      name: unverified
                    provider: rabbitmq
                    clusters:
                      blue:
                        management: https://blue.internal:15671
                        amqp: amqps://blue.internal:5671
                        username: cutover
                        password: s3cret
                        tls:
                          verify: false
                    """);

            assertThat(warnings(report)).contains(
                    "tls.verify is false — the management document this reads is a credential");
        }
    }

    @Nested
    class TheEndpoint {

        @Test
        void warnsWhenAbsent() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("endpoint:\n  kind: external\n"
                            + "  description: switched by the platform team\n", ""));

            assertThat(warnings(report)).contains(
                    "absent — a cutover that never moves the endpoint leaves clients on the source");
        }

        @Test
        void requiresAKind() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("  kind: external\n", ""));

            assertThat(errors(report))
                    .contains("is required; expected one of [external, hook]");
        }

        /** The description is not documentation. It is what a human is shown when the plan stops. */
        @Test
        void requiresADescriptionForAnExternalSwitch() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("  description: switched by the platform team\n", ""));

            assertThat(errors(report)).contains("kind: external must carry a description — it is "
                    + "what a human is shown when the plan stops");
        }

        @Test
        void requiresACommandForAHook() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("  kind: external\n  description: switched by the platform team\n",
                            "  kind: hook\n"));

            assertThat(errors(report)).contains("kind: hook must carry a run command");
        }

        @Test
        void acceptsAHookWithACommand() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN)
                    .replace("  kind: external\n  description: switched by the platform team\n",
                            "  kind: hook\n  run: ./switch-endpoint.sh\n  timeout: 2m\n"));

            assertThat(report.errors()).isEmpty();
        }
    }

    @Nested
    class TheDeployment {

        @Test
        void requiresADeploymentBlock() {
            ValidationReport report = validate(around(""));

            assertThat(report.errors()).anySatisfy(finding -> {
                assertThat(finding.where()).isEqualTo("deployment");
                assertThat(finding.message()).isEqualTo("is required");
            });
        }

        @Test
        void requiresAnOperation() {
            ValidationReport report = validate(around("""
                    deployment:
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(errors(report)).contains(
                    "is required; expected one of [blueGreen, canary, mirror]");
        }

        @Test
        void requiresFromAndTo() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      semantics: atLeastOnce
                    """));

            assertThat(report.errors()).extracting(Finding::where)
                    .contains("deployment.from", "deployment.to");
        }

        @Test
        void refusesAClusterThatIsNotThere() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: purple
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(errors(report)).contains("no cluster named 'purple'");
        }

        /** The most consequential field in the file, and the tool will not fill it in. */
        @Test
        void requiresSemantics() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                    """));

            assertThat(report.errors()).anySatisfy(finding -> {
                assertThat(finding.where()).isEqualTo("deployment.semantics");
                assertThat(finding.message()).isEqualTo("is required and has no default. "
                        + "atLeastOnce means a message may be processed on both clusters; "
                        + "atMostOnce means one may be stranded. The tool will not choose this "
                        + "for you (docs/message-state.md)");
            });
        }

        @Test
        void doesNotAskAMirrorForSemantics() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: ["orders"]
                    """));

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void warnsWhenAMirrorCarriesSemantics() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      mirror:
                        exchanges: ["orders"]
                    """));

            assertThat(warnings(report))
                    .contains("a mirror moves nothing; semantics has no meaning here");
        }
    }

    @Nested
    class Canaries {

        @Test
        void requiresAScope() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(errors(report)).contains("a canary must enumerate its scope — the unit is "
                    + "a queue and everything attached to it");
        }

        @Test
        void requiresQueuesAndServices() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      scope:
                        vhost: /orders
                    """));

            assertThat(report.errors()).extracting(Finding::where)
                    .contains("deployment.scope.queues", "deployment.scope.services");
        }

        /**
         * The refusal this operation exists for, in every spelling anybody reaches for. The word
         * is not the problem: somebody who writes {@code weight: 5} after {@code percentage: 5} is
         * refused has not been helped by the first refusal.
         */
        @Test
        void refusesEverySpellingOfATrafficSplit() {
            for (String key : new String[] {
                    "percentage", "percent", "weight", "split", "trafficSplit"}) {
                ValidationReport report = validate(around("""
                        deployment:
                          operation: canary
                          from: blue
                          to: green
                          semantics: atLeastOnce
                          scope:
                            queues: ["orders.notifications"]
                            services: [notification-service]
                            %s: 5
                        """.formatted(key)));

                assertThat(errors(report))
                        .describedAs("scope.%s", key)
                        .anyMatch(message -> message.startsWith(
                                "'" + key + "' has no meaning for a broker."));
            }
        }

        @Test
        void refusesATrafficSplitOnTheDeploymentToo() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      percentage: 5
                      scope:
                        queues: ["orders.notifications"]
                        services: [notification-service]
                    """));

            assertThat(report.errors()).anySatisfy(finding ->
                    assertThat(finding.where()).isEqualTo("deployment.percentage"));
        }

        /**
         * Only a canary. A key called {@code weight} on a blue/green is a typo and is reported as
         * an unknown key would be, not with four sentences about partitioned queues.
         */
        @Test
        void saysNothingAboutSplitsOnABlueGreen() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      weight: 5
                    """));

            assertThat(errors(report)).noneMatch(message -> message.contains("partitions the queue"));
        }

        /**
         * The estate-wide close hiding inside a canary, and it is visible with no broker at all.
         *
         * <p>An empty {@code select.users} means every consuming connection in the virtual host.
         * That is what a whole-estate cutover means and it is right there; in a canary it takes
         * down the workloads that are staying, which is the outage the operation was chosen to
         * avoid.
         */
        @Test
        void refusesACanaryThatClosesEveryConsumingConnection() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      scope:
                        queues: ["orders.notifications"]
                        services: [notification-service]
                      steps:
                        - id: drain-consumers
                          closeConnections:
                            on: blue
                            select: { role: consumer }
                            after: { unacked: 0, timeout: 3m }
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.contains("no select.users, which is every consuming connection")
                            && message.contains("notification-service"));
        }

        /** A file disagreeing with itself about which workload is moving. */
        @Test
        void refusesACanaryThatClosesAServiceItsScopeDoesNotName() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      scope:
                        queues: ["orders.notifications"]
                        services: [notification-service]
                      steps:
                        - id: drain-consumers
                          closeConnections:
                            on: blue
                            select: { users: [notification-service, orders-service] }
                            after: { unacked: 0, timeout: 3m }
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.contains("closes connections for orders-service")
                            && message.contains("have to be the same list"));
        }
    }

    @Nested
    class StreamBlock {

        /**
         * {@code restartAt} on its own reads as a setting, and nothing here sets an offset.
         */
        @Test
        void refusesARestartPositionNobodyHasAcknowledged() {
            ValidationReport report = validate("streams:\n  restartAt: next\n" + around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.contains("restartAt does not move an offset")
                            && message.contains("The confirmation is acknowledged"));
        }

        @Test
        void warnsThatAcknowledgedFalseIsTheSameAsSayingNothing() {
            ValidationReport report = validate("streams:\n  acknowledged: false\n" + around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(warnings(report)).anyMatch(message ->
                    message.contains("acknowledged is false"));
        }

        @Test
        void acceptsTheBlockTheDocumentationWritesOut() {
            ValidationReport report = validate("""
                    streams:
                      acknowledged: true
                      restartAt: next
                      note: "audit.events consumers accept the gap; replay from the warehouse"
                    """ + around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(errors(report)).isEmpty();
        }
    }

    @Nested
    class Mirrors {

        @Test
        void requiresAMirrorBlock() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                    """));

            assertThat(errors(report)).contains("is required for a mirror operation");
        }

        @Test
        void refusesQueueFederation() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        queues: ["orders.new"]
                    """));

            assertThat(errors(report)).contains("mirror.queues asks for queue federation, which "
                    + "pulls only when the upstream has no local consumers — a conditional MOVE, "
                    + "not a copy. A mirror federates exchanges: use mirror.exchanges "
                    + "(docs/message-state.md)");
        }

        @Test
        void requiresExchanges() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        upstream:
                          uri: amqps://blue.internal:5671
                    """));

            assertThat(errors(report))
                    .contains("mirror.exchanges is required — a mirror federates exchanges");
        }

        /** A drain consumes from the source, and a mirror is an observation. */
        @Test
        void refusesADrainInsideAMirror() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: ["orders"]
                      steps:
                        - id: start-mirror
                          mirror:
                            from: blue
                            to: green
                            exchanges: ["orders"]
                        - id: sneaky-drain
                          drain:
                            from: blue
                            to: green
                    """));

            assertThat(errors(report)).contains("a mirror must not drain — a drain consumes from "
                    + "the source and a mirror is an observation");
        }

        /**
         * A mirror does not end in a cutover, which is docs/canary.md's reason for giving it its
         * own verb rather than making it a mode of canary.
         */
        @Test
        void refusesAnEndpointSwitchInsideAMirror() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: ["orders"]
                      steps:
                        - id: start-mirror
                          mirror:
                            from: blue
                            to: green
                            exchanges: ["orders"]
                        - id: switch-endpoint
                          endpoint:
                            target: green
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.contains("a mirror switches no endpoint")
                            && message.contains("meant to discard"));
        }

        @Test
        void warnsThatAMirrorHasNothingToRollBack() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: ["orders"]

                    rollback:
                      keep: blue
                    """));

            assertThat(warnings(report))
                    .anyMatch(message -> message.contains("a mirror has no rollback"));
        }
    }

    @Nested
    class Backups {

        @Test
        void warnsWhenAbsent() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                    """));

            assertThat(warnings(report)).contains("absent — nothing captures the source's "
                    + "definitions before the cutover touches anything");
        }

        @Test
        void doesNotAskAMirrorForABackup() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: ["orders"]
                    """));

            assertThat(warnings(report)).noneMatch(message -> message.contains("definitions"));
        }

        @Test
        void warnsWhenRedactionIsTurnedOff() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup:
                        enabled: true
                        redactCredentials: false
                    """));

            assertThat(warnings(report)).contains("redactCredentials is off. A definitions export "
                    + "carries password hashes; the file it writes is a credential");
        }
    }

    @Nested
    class Steps {

        @Test
        void warnsWhenTheListIsAbsent() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup:
                        enabled: true
                    """));

            assertThat(warnings(report)).anyMatch(message ->
                    message.startsWith("absent; the default step list for this operation"));
        }

        @Test
        void refusesAnEmptyList() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps: []
                    """));

            assertThat(errors(report)).contains("must be a non-empty list");
        }

        @Test
        void requiresAnIdOnEveryStep() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - announce: {}
                    """));

            assertThat(errors(report)).contains("every step needs an id — the plan, the status "
                    + "output and the errors all refer to it");
        }

        @Test
        void refusesADuplicateId() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: topology
                          copyTopology: { from: blue, to: green, exclude: [policies, operatorPolicies] }
                        - id: topology
                          announce: {}
                    """));

            assertThat(errors(report)).contains("duplicate step id 'topology'");
        }

        @Test
        void refusesAStepThatDoesNothing() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: idle
                    """));

            assertThat(errors(report)).contains("no action; expected a waitFor, or one of "
                    + "[announce, closeConnections, copyTopology, drain, endpoint, mirror, requires]");
        }

        /** Waiting is a thing a step can do. `pause-producers` changes nothing and blocks. */
        @Test
        void acceptsAStepThatOnlyWaits() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup:
                        enabled: true
                      steps:
                        - id: pause-producers
                          waitFor:
                            on: blue
                            publishRate: 0
                            timeout: 2m
                    """));

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void refusesAStepThatDoesTwoThings() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: busy
                          announce: {}
                          endpoint: { target: green }
                    """));

            assertThat(errors(report))
                    .contains("2 actions (announce, endpoint); a step does exactly one thing");
        }

        @Test
        void refusesUnknownKeysOnAStep() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: typo
                          announce: {}
                          waitfor: {}
                          retries: 3
                    """));

            assertThat(errors(report)).contains("unknown keys: retries, waitfor");
        }

        @Test
        void requiresBothEndsOfADrain() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: drain-messages
                          drain:
                            queues: ["orders.*"]
                    """));

            assertThat(errors(report)).contains("drain.from is required", "drain.to is required");
        }

        /**
         * A topology copy may omit them: a file that has named its clusters once at the deployment
         * does not have to repeat them on every step. A drain is a broker-side operation declared
         * between two named endpoints, so its ends are required.
         */
        @Test
        void letsATopologyCopyOmitThem() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup:
                        enabled: true
                      steps:
                        - id: topology
                          copyTopology:
                            exclude: [policies, operatorPolicies]
                    """));

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void refusesAnEndOfADrainThatNamesNoCluster() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: drain-messages
                          drain:
                            from: blue
                            to: purple
                    """));

            assertThat(errors(report)).contains("drain.to: no cluster named 'purple'");
        }

        @Test
        void refusesAnEndpointTargetThatNamesNoCluster() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: switch-endpoint
                          endpoint: { target: purple }
                    """));

            assertThat(errors(report)).contains("endpoint.target: no cluster named 'purple'");
        }

        @Test
        void refusesAGuardOnAClusterThatIsNotThere() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: verify
                          waitFor:
                            on: purple
                            depth: 0
                            timeout: 5m
                    """));

            assertThat(errors(report)).contains("waitFor.on: no cluster named 'purple'");
        }

        @Test
        void refusesAGuardWithNoTimeout() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: verify
                          waitFor:
                            on: green
                            depth: 0
                    """));

            assertThat(errors(report)).contains("waitFor without a timeout waits forever");
        }

        /** The id is in the field name, because a reader counting list entries by hand gets it wrong. */
        @Test
        void namesTheStepByItsId() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: probe
                          announce: {}
                        - id: drain-messages
                          drain: { from: blue, to: purple }
                    """));

            assertThat(report.errors()).anySatisfy(finding ->
                    assertThat(finding.where()).isEqualTo("deployment.steps[1](drain-messages)"));
        }
    }

    /**
     * The rules that know something about brokers.
     *
     * <p>Everything above is the documented schema, checked. These are the ones that exist because
     * of docs/message-state.md, and each of them refuses an arrangement that loses messages while
     * looking entirely reasonable in review.
     */
    @Nested
    class Ordering {

        @Test
        void refusesPoliciesCopiedBeforeTheDrain() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: topology
                          copyTopology:
                            from: blue
                            to: green
                            include: [exchanges, queues, policies]
                        - id: pause-producers
                          waitFor: { on: blue, publishRate: 0, timeout: 2m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.startsWith("copies policies to the target before the drain."));
        }

        /**
         * No include list means everything, policies included. This is the arrangement the first
         * version of the Python check got wrong in the other direction, by flagging every step in
         * the file — so it is tested from both sides.
         */
        @Test
        void refusesACopyOfEverythingBeforeTheDrain() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: topology
                          copyTopology: { from: blue, to: green }
                        - id: pause-producers
                          waitFor: { on: blue, publishRate: 0, timeout: 2m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.startsWith("copies policies to the target before the drain."));
        }

        @Test
        void acceptsACopyThatExcludesThem() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup: { enabled: true }
                      steps:
                        - id: topology
                          copyTopology:
                            from: blue
                            to: green
                            exclude: [policies, operatorPolicies]
                        - id: pause-producers
                          waitFor: { on: blue, publishRate: 0, timeout: 2m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                        - id: policies
                          copyTopology:
                            from: blue
                            to: green
                            include: [policies, operatorPolicies]
                    """));

            assertThat(report.errors()).isEmpty();
        }

        /** Half an exclusion is no exclusion: an operator policy eats a backlog just as well. */
        @Test
        void refusesAnExclusionThatMissesOperatorPolicies() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: topology
                          copyTopology:
                            from: blue
                            to: green
                            exclude: [policies]
                        - id: pause-producers
                          waitFor: { on: blue, publishRate: 0, timeout: 2m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(errors(report)).anyMatch(message ->
                    message.startsWith("copies policies to the target before the drain."));
        }

        @Test
        void refusesADrainThatNothingSettledFirst() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(errors(report)).anyMatch(message -> message.startsWith(
                    "drains with no preceding waitFor on publishRate or unacked."));
        }

        @Test
        void acceptsADrainAfterAWaitOnUnacked() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup: { enabled: true }
                      steps:
                        - id: drain-consumers
                          closeConnections:
                            on: blue
                            select: { users: [orders-service], role: consumer }
                          waitFor: { on: blue, unacked: 0, timeout: 5m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(report.errors()).isEmpty();
        }

        /** A guard on depth is not a settle: a cluster can be empty and still being published to. */
        @Test
        void aWaitOnDepthAloneDoesNotSettleTheSource() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: nearly
                          waitFor: { on: blue, depth: 0, timeout: 5m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                          waitFor: { on: blue, depth: 0, timeout: 15m }
                    """));

            assertThat(errors(report)).anyMatch(message -> message.startsWith(
                    "drains with no preceding waitFor on publishRate or unacked."));
        }

        @Test
        void warnsAboutADrainWithNoGuardOfItsOwn() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup: { enabled: true }
                      steps:
                        - id: pause-producers
                          waitFor: { on: blue, publishRate: 0, timeout: 2m }
                        - id: drain-messages
                          drain: { from: blue, to: green }
                    """));

            assertThat(warnings(report)).contains("no waitFor on the drain — nothing confirms the "
                    + "source emptied before the next step runs");
        }

        @Test
        void saysNothingWhenThereIsNoDrain() {
            ValidationReport report = validate(around("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      backup: { enabled: true }
                      steps:
                        - id: topology
                          copyTopology: { from: blue, to: green }
                        - id: switch-endpoint
                          endpoint: { target: green }
                    """));

            assertThat(report.errors()).isEmpty();
        }
    }

    /**
     * The rollback block, which scripts/lint-deployment.py does not look at.
     *
     * <p>The interesting half of this is the rule that is deliberately <em>not</em> applied. A
     * rollback drains with nothing having settled first because the cutover it is undoing already
     * stopped the producers and closed the consumers; applying the ordering rule here would refuse
     * every correct rollback in examples/.
     */
    @Nested
    class Rollbacks {

        @Test
        void checksTheClusterItKeeps() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN + """
                    rollback:
                      keep: purple
                      for: 72h
                    """));

            assertThat(report.errors()).anySatisfy(finding -> {
                assertThat(finding.where()).isEqualTo("rollback.keep");
                assertThat(finding.message()).isEqualTo("no cluster named 'purple'");
            });
        }

        @Test
        void checksTheStepsStructurally() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN + """
                    rollback:
                      keep: blue
                      steps:
                        - id: drain-back
                          drain: { from: green, to: purple }
                          waitFor: { on: green, depth: 0 }
                    """));

            assertThat(report.errors()).extracting(Finding::where)
                    .containsOnly("rollback.steps[0](drain-back)");
            assertThat(errors(report)).containsExactlyInAnyOrder(
                    "drain.to: no cluster named 'purple'",
                    "waitFor without a timeout waits forever");
        }

        @Test
        void doesNotDemandASettleBeforeARollbackDrain() {
            ValidationReport report = validate(around(CLEAN_BLUE_GREEN + """
                    rollback:
                      keep: blue
                      for: 72h
                      steps:
                        - id: switch-endpoint
                          endpoint: { target: blue }
                        - id: drain-back
                          drain: { from: green, to: blue, queues: ["orders.*"] }
                          waitFor: { on: green, depth: 0, timeout: 15m }
                    """));

            assertThat(report.errors()).isEmpty();
        }
    }
}
