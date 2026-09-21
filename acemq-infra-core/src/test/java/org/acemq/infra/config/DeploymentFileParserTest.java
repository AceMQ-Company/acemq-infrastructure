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
package org.acemq.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.List;

import org.acemq.infra.Fixtures;
import org.acemq.infra.provider.Capability;
import org.junit.jupiter.api.Test;

/**
 * The documented format, field by field, and the failures the parser owns.
 *
 * <p>The division being tested here is the one {@link ConfigException} describes: a shape that
 * cannot be represented is a parse failure, and an absence is not. A test that asserted a missing
 * {@code semantics} threw would be asserting the wrong half of the design.
 */
class DeploymentFileParserTest {

    /**
     * The worked example from docs/configuration.md, read back field by field.
     *
     * <p>Long on purpose. The format is the interface, and the only way to know that every
     * documented field survives the trip is to name every documented field.
     */
    @Test
    void readsEveryFieldTheDocumentedFormatDefines() {
        DeploymentFile file = Fixtures.parse("""
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: orders-blue-green
                clusters:
                  blue:
                    management: https://blue.internal:15671
                    amqp:       amqps://blue.internal:5671
                    vhost:      /orders
                    username:   cutover
                    password:   s3cret
                    tls:
                      verify: true
                      caFile: /etc/ssl/ca.pem
                  green:
                    management: https://green.internal:15671
                    amqp:       amqps://green.internal:5671
                    vhost:      /orders
                    username:   cutover
                    password:   s3cret
                provider: rabbitmq
                providerConfig:
                  someKnob: 7
                endpoint:
                  kind: external
                  description: a CNAME switched by the platform team
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  backup:
                    enabled: true
                    path: ./backups/{{name}}-{{timestamp}}.json
                    redactCredentials: true
                  announce:
                    exchange: orders.events
                    routingKey: deployment.started
                    payload: |
                      {"status": "started"}
                  steps:
                    - id: probe
                      requires:
                        - TOPOLOGY_EXPORT
                        - DRAIN_BY_SHOVEL
                    - id: topology
                      copyTopology:
                        from: blue
                        to: green
                        vhosts: ["/orders"]
                        include: [exchanges, queues, bindings, users, permissions, parameters]
                        exclude: [policies, operatorPolicies]
                    - id: announce-drain
                      announce: {}
                    - id: pause-producers
                      waitFor:
                        on: blue
                        publishRate: 0
                        timeout: 2m
                        onTimeout: prompt
                    - id: drain-consumers
                      closeConnections:
                        on: blue
                        select:
                          users: [orders-service]
                          role: consumer
                        after:
                          unacked: 0
                          timeout: 5m
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
                        timeout: 15m
                        onTimeout: abort
                    - id: switch-endpoint
                      endpoint:
                        target: green
                    - id: verify
                      waitFor:
                        on: green
                        consumers: { min: 1, max: 8 }
                        timeout: 5m
                rollback:
                  keep: blue
                  for: 72h
                  steps:
                    - id: switch-endpoint
                      endpoint: { target: blue }
                streams:
                  acknowledged: true
                """);

        assertThat(file.apiVersion()).contains("acemq.org/v1alpha1");
        assertThat(file.kind()).contains("Deployment");
        assertThat(file.metadata().name()).contains("orders-blue-green");
        assertThat(file.provider()).contains("rabbitmq");
        assertThat(file.providerConfig()).isPresent();
        assertThat(file.unknownKeys()).isEmpty();
        assertThat(file.streams().orElseThrow().acknowledged()).contains(true);

        Cluster blue = file.clusters().get("blue");
        assertThat(blue.management()).contains("https://blue.internal:15671");
        assertThat(blue.amqp()).contains("amqps://blue.internal:5671");
        assertThat(blue.vhost()).contains("/orders");
        assertThat(blue.username()).contains("cutover");
        assertThat(blue.password()).contains("s3cret");
        assertThat(blue.tls().orElseThrow().verify()).contains(true);
        assertThat(blue.tls().orElseThrow().caFile()).contains("/etc/ssl/ca.pem");
        assertThat(file.clusters().get("green").tls()).isEmpty();

        Endpoint endpoint = file.endpoint().orElseThrow();
        assertThat(endpoint.kind()).contains(EndpointKind.EXTERNAL);
        assertThat(endpoint.description()).contains("a CNAME switched by the platform team");

        Deployment deployment = file.deployment().orElseThrow();
        assertThat(deployment.operation()).contains(Operation.BLUE_GREEN);
        assertThat(deployment.from()).contains("blue");
        assertThat(deployment.to()).contains("green");
        assertThat(deployment.semantics()).contains(Semantics.AT_LEAST_ONCE);
        assertThat(deployment.backup().orElseThrow().enabled()).contains(true);
        assertThat(deployment.backup().orElseThrow().path())
                .contains("./backups/{{name}}-{{timestamp}}.json");
        assertThat(deployment.backup().orElseThrow().redactCredentials()).contains(true);
        assertThat(deployment.announce().orElseThrow().exchange()).contains("orders.events");
        assertThat(deployment.announce().orElseThrow().routingKey()).contains("deployment.started");
        assertThat(deployment.announce().orElseThrow().payload())
                .contains("{\"status\": \"started\"}\n");

        List<Step> steps = deployment.stepsOrEmpty();
        assertThat(steps).hasSize(8);

        assertThat(steps.get(0).find(Action.Requires.class).orElseThrow().capabilities())
                .containsExactly(Capability.TOPOLOGY_EXPORT, Capability.DRAIN_BY_SHOVEL);

        Action.CopyTopology copy = steps.get(1).find(Action.CopyTopology.class).orElseThrow();
        assertThat(copy.from()).contains("blue");
        assertThat(copy.to()).contains("green");
        assertThat(copy.vhosts()).containsExactly("/orders");
        assertThat(copy.include()).containsExactly(TopologyPart.EXCHANGES, TopologyPart.QUEUES,
                TopologyPart.BINDINGS, TopologyPart.USERS, TopologyPart.PERMISSIONS,
                TopologyPart.PARAMETERS);
        assertThat(copy.exclude())
                .containsExactly(TopologyPart.POLICIES, TopologyPart.OPERATOR_POLICIES);

        assertThat(steps.get(2).action()).containsInstanceOf(Action.Announce.class);

        WaitFor pause = steps.get(3).waitFor().orElseThrow();
        assertThat(pause.on()).contains("blue");
        assertThat(pause.publishRate()).hasValue(0);
        assertThat(pause.timeout()).contains(Duration.ofMinutes(2));
        assertThat(pause.onTimeout()).contains(OnTimeout.PROMPT);
        assertThat(pause.settlesTheSource()).isTrue();

        Action.CloseConnections close =
                steps.get(4).find(Action.CloseConnections.class).orElseThrow();
        assertThat(close.on()).contains("blue");
        assertThat(close.select().orElseThrow().users()).containsExactly("orders-service");
        assertThat(close.select().orElseThrow().role()).contains("consumer");
        assertThat(close.after().orElseThrow().unacked()).hasValue(0);
        assertThat(close.after().orElseThrow().timeout()).contains(Duration.ofMinutes(5));
        assertThat(close.after().orElseThrow().onTimeout()).contains(OnTimeout.ABORT);

        Action.Drain drain = steps.get(5).find(Action.Drain.class).orElseThrow();
        assertThat(drain.from()).contains("blue");
        assertThat(drain.to()).contains("green");
        assertThat(drain.queues()).containsExactly("orders.*", "!orders.audit");
        assertThat(drain.ackMode()).contains("onConfirm");
        assertThat(drain.deleteAfter()).contains("queueLength");
        assertThat(steps.get(5).waitFor().orElseThrow().depth()).hasValue(0);

        assertThat(steps.get(6).find(Action.Switch.class).orElseThrow().target()).contains("green");

        WaitFor verify = steps.get(7).waitFor().orElseThrow();
        assertThat(verify.consumers().orElseThrow().min()).hasValue(1);
        assertThat(verify.consumers().orElseThrow().max()).hasValue(8);
        assertThat(verify.onTimeout()).isEmpty();

        Rollback rollback = file.rollback().orElseThrow();
        assertThat(rollback.keep()).contains("blue");
        assertThat(rollback.keepFor()).contains(Duration.ofHours(72));
        assertThat(rollback.stepsOrEmpty()).hasSize(1);
    }

    @Test
    void readsAMirrorBlock() {
        DeploymentFile file = Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: mirror
                  from: blue
                  to: green
                  mirror:
                    exchanges: ["orders"]
                    upstream:
                      uri: amqps://blue.internal:5671
                      prefetch: 1000
                      ackMode: onConfirm
                """));

        MirrorSpec mirror = file.deployment().orElseThrow().mirror().orElseThrow();
        assertThat(mirror.exchanges()).containsExactly("orders");
        assertThat(mirror.queues()).isEmpty();
        assertThat(mirror.upstream().orElseThrow().uri()).contains("amqps://blue.internal:5671");
        assertThat(mirror.upstream().orElseThrow().prefetch()).hasValue(1000);
        assertThat(mirror.upstream().orElseThrow().ackMode()).contains("onConfirm");
    }

    /** Absent and empty are different questions, and the validator answers them differently. */
    @Test
    void tellsAnAbsentStepListFromAnEmptyOne() {
        DeploymentFile absent = Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                """));
        DeploymentFile empty = Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps: []
                """));

        assertThat(absent.deployment().orElseThrow().steps()).isEmpty();
        assertThat(empty.deployment().orElseThrow().steps()).contains(List.of());
    }

    /** Collapsing to the first action would silently drop the second one somebody wrote. */
    @Test
    void keepsEveryActionAStepWrites() {
        DeploymentFile file = Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps:
                    - id: doing-two-things
                      drain: { from: blue, to: green }
                      announce: {}
                """));

        Step step = file.deployment().orElseThrow().stepsOrEmpty().get(0);
        assertThat(step.actions()).hasSize(2);
        assertThat(step.action()).isEmpty();
    }

    @Test
    void keepsUnknownKeysWithWhereTheyWereWritten() {
        DeploymentFile file = Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: canary
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  scope:
                    queues: ["orders.notifications"]
                    percentage: 5
                stowaway: true
                """));

        assertThat(file.unknownKeys()).extracting(UnknownKey::qualified)
                .containsExactly("stowaway");
        assertThat(file.deployment().orElseThrow().scope().orElseThrow().unknownKeys())
                .extracting(UnknownKey::qualified)
                .containsExactly("deployment.scope.percentage");
    }

    // ------------------------------------------------------ what the parser refuses

    @Test
    void refusesAWordOutsideAClosedVocabulary() {
        String source = Fixtures.around("""
                deployment:
                  operation: rollingUpdate
                  from: blue
                  to: green
                  semantics: atLeastOnce
                """);

        ConfigException error =
                catchThrowableOfType(() -> Fixtures.parse(source), ConfigException.class);

        assertThat(error.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.path()).isEqualTo("deployment.operation");
            assertThat(problem.message()).isEqualTo(
                    "'rollingUpdate' is not an operation; expected one of [blueGreen, canary, mirror]");
            // Read back out of the source rather than hardcoded: a line number asserted as a
            // constant tests the length of the fixture's preamble and nothing else.
            assertThat(source.lines().toList().get(problem.location().line() - 1))
                    .contains("operation: rollingUpdate");
        });
    }

    @Test
    void refusesAnUnknownCapability() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps:
                    - id: probe
                      requires: [DRAIN_BY_SHOVEL, TELEPORT_MESSAGES]
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'TELEPORT_MESSAGES' is not a capability")
                .hasMessageContaining("docs/broker-agnostic.md");
    }

    @Test
    void refusesAnUnknownTopologyPart() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
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
                        include: [exchanges, shovels]
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'shovels' is not a topology part");
    }

    @Test
    void refusesATimeoutThatIsNotADuration() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps:
                    - id: pause
                      waitFor:
                        on: blue
                        publishRate: 0
                        timeout: 15 minutes
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'15 minutes' is not a duration")
                .hasMessageContaining("such as 15m, 90s or 72h");
    }

    @Test
    void refusesAFieldOfTheWrongShape() {
        assertThatThrownBy(() -> Fixtures.parse("""
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata: orders-blue-green
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("metadata")
                .hasMessageContaining("expected a mapping, found a value");
    }

    @Test
    void refusesAListWhereAValueBelongs() {
        assertThatThrownBy(() -> Fixtures.parse("""
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                provider: [rabbitmq, kafka]
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("provider: expected a value, found a sequence");
    }

    @Test
    void refusesADocumentThatIsNotAMapping() {
        assertThatThrownBy(() -> Fixtures.parse("- one\n- two\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("the document must be a mapping, found a sequence");
    }

    @Test
    void refusesStepsThatAreNotAList() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps: probe, topology, drain
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("deployment.steps: expected a list of steps, found a value");
    }

    @Test
    void refusesANumberWhereTrueOrFalseBelongs() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  backup:
                    enabled: 1
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("deployment.backup.enabled: expected true or false, found '1'");
    }

    @Test
    void refusesTextWhereANumberBelongs() {
        assertThatThrownBy(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  steps:
                    - id: verify
                      waitFor:
                        on: green
                        depth: empty
                        timeout: 5m
                """)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(
                        "waitFor.depth: expected a whole number, found 'empty'");
    }

    /** A reader with four things wrong in their file should be told about all four, once. */
    @Test
    void reportsEveryProblemInOneRun() {
        ConfigException error = catchThrowableOfType(() -> Fixtures.parse(Fixtures.around("""
                deployment:
                  operation: rollingUpdate
                  from: blue
                  to: green
                  semantics: exactlyOnce
                  steps:
                    - id: pause
                      waitFor:
                        on: blue
                        publishRate: 0
                        timeout: forever
                        onTimeout: panic
                """)), ConfigException.class);

        assertThat(error.problems()).extracting(ConfigException.Problem::path).containsExactly(
                "deployment.operation",
                "deployment.semantics",
                "deployment.steps[0](pause).waitFor.timeout",
                "deployment.steps[0](pause).waitFor.onTimeout");
        assertThat(error).hasMessageContaining("4 problems:");
    }

    /** The path the caller typed, not the absolute one: every message is going to repeat it. */
    @Test
    void namesTheFileTheCallerNamed() {
        DeploymentFile file = DeploymentFileParser.parse(
                Fixtures.around(""), "deployments/orders.yaml", Fixtures.ENVIRONMENT);

        assertThat(file.origin()).isEqualTo("deployments/orders.yaml");
        assertThat(file.location().file()).isEqualTo("deployments/orders.yaml");
    }
}
