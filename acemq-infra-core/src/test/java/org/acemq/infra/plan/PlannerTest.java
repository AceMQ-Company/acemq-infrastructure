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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.acemq.infra.Fixtures;
import org.acemq.infra.config.Action;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.Inventory;
import org.acemq.infra.provider.ProbedCluster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The planner, tested without a broker, which is the whole reason it is shaped the way it is.
 *
 * <p>Every cluster in here is a constructed value. That buys estates a test could not otherwise
 * have: a source with the shovel plugin missing, a target on a version too old for operator
 * policies, a file whose drain names a cluster nobody probed. Each of those is a morning's work to
 * arrange against real brokers and a line and a half here, and they are exactly the cases where a
 * cutover tool is supposed to stop rather than start.
 */
class PlannerTest {

    /** The worked example from docs/configuration.md, which is also the file in examples/. */
    private static final String CUTOVER = """
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
              steps:
                - id: probe
                  requires: [TOPOLOGY_EXPORT, TOPOLOGY_IMPORT_MERGE, DRAIN_BY_SHOVEL]
                - id: topology
                  copyTopology:
                    from: blue
                    to: green
                    include: [exchanges, queues, bindings]
                    exclude: [policies, operatorPolicies]
                - id: announce-drain
                  announce: {}
                - id: pause-producers
                  waitFor: { on: blue, publishRate: 0, timeout: 2m, onTimeout: prompt }
                - id: drain-consumers
                  closeConnections:
                    on: blue
                    select: { users: [orders-service], role: consumer }
                    after: { unacked: 0, timeout: 5m, onTimeout: abort }
                - id: drain-messages
                  drain:
                    from: blue
                    to: green
                    queues: ["orders.*", "!orders.audit"]
                  waitFor: { on: blue, depth: 0, timeout: 15m, onTimeout: abort }
                - id: policies
                  copyTopology:
                    from: blue
                    to: green
                    include: [policies, operatorPolicies]
                - id: switch-endpoint
                  endpoint: { target: green }
                - id: verify
                  waitFor: { on: green, consumers: { min: 1 }, timeout: 5m, onTimeout: abort }
            """;

    private static Plan plan(String body) {
        return plan(body, Probes.blue().build(), Probes.green().build());
    }

    private static Plan plan(String body, ProbedCluster source, ProbedCluster target) {
        DeploymentFile file = Fixtures.parse(Fixtures.around(body));
        return Planner.plan(file, source, target);
    }

    /** The lines of one step, found by its id, for the tests that are about one step's text. */
    private static List<String> step(Plan plan, String id) {
        return plan.steps().stream().filter(step -> step.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no step " + id + " in " + plan.render()))
                .lines();
    }

    private static int number(Plan plan, String id) {
        return plan.steps().stream().filter(step -> step.id().equals(id)).findFirst()
                .orElseThrow().number();
    }

    @Nested
    @DisplayName("the header and the probe summary")
    class Header {

        @Test
        @DisplayName("names the deployment, the operation, both clusters and the semantics")
        void headline() {
            assertThat(plan(CUTOVER).render()).startsWith(
                    "test-deployment — blueGreen, blue → green, semantics=atLeastOnce\n");
        }

        @Test
        @DisplayName("reports each cluster's version and plugins before anything else")
        void probeBlock() {
            assertThat(plan(CUTOVER).render()).contains(
                    "  probe             blue  RabbitMQ 3.13.7  shovel✓ federation✓ streams✓\n"
                            + "                    green RabbitMQ 4.0.5   shovel✓ federation✓ streams✓\n");
        }

        @Test
        @DisplayName("says how many required capabilities were satisfied")
        void requirementsMet() {
            assertThat(plan(CUTOVER).render()).contains("all 3 required capabilities present");
        }

        @Test
        @DisplayName("names the missing ones instead, when there are any")
        void requirementsMissing() {
            Plan plan = plan(CUTOVER, Probes.blue()
                    .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled").build(),
                    Probes.green().build());
            assertThat(plan.render())
                    .contains("3 required capabilities, 1 missing: DRAIN_BY_SHOVEL");
        }

        @Test
        @DisplayName("repeats what the probe could not determine")
        void notes() {
            Plan plan = plan(CUTOVER, Probes.blue().note("tls.verify is ignored").build(),
                    Probes.green().build());
            assertThat(plan.render()).contains("blue: tls.verify is ignored");
        }
    }

    @Nested
    @DisplayName("the numbered steps")
    class Steps {

        @Test
        @DisplayName("start at the backup and leave the probe step to the summary")
        void numbering() {
            Plan plan = plan(CUTOVER);
            assertThat(plan.steps()).extracting(PlannedStep::id).containsExactly("backup",
                    "topology", "announce-drain", "pause-producers", "drain-consumers",
                    "drain-messages", "policies", "switch-endpoint", "verify");
            assertThat(plan.steps()).extracting(PlannedStep::number)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        }

        @Test
        @DisplayName("renumber when the backup is turned off")
        void withoutBackup() {
            Plan plan = plan(CUTOVER.replace("enabled: true", "enabled: false"));
            assertThat(plan.steps()).extracting(PlannedStep::id).doesNotContain("backup");
            assertThat(number(plan, "topology")).isEqualTo(1);
        }

        @Test
        @DisplayName("write the backup where the file said, with the deployment's name in it")
        void backupPath() {
            assertThat(step(plan(CUTOVER), "backup")).containsExactly(
                    "blue definitions → ./backups/test-deployment-{{timestamp}}.json",
                    "credentials redacted");
        }

        @Test
        @DisplayName("say so when the backup will carry password hashes")
        void backupUnredacted() {
            Plan plan = plan(CUTOVER.replace("redactCredentials: true", "redactCredentials: false"));
            assertThat(step(plan, "backup").get(1))
                    .isEqualTo("credentials NOT redacted — the file is a credential, written 0600");
        }

        @Test
        @DisplayName("count what the topology copy will carry, from the source's own inventory")
        void topologyCounts() {
            assertThat(step(plan(CUTOVER), "topology").get(0))
                    .isEqualTo("14 exchanges, 31 queues, 58 bindings");
        }

        @Test
        @DisplayName("name the step the excluded policies are applied at")
        void exclusionPointsForward() {
            Plan plan = plan(CUTOVER);
            assertThat(step(plan, "topology").get(1)).isEqualTo(
                    "policies, operatorPolicies EXCLUDED — applied at step "
                            + number(plan, "policies"));
        }

        @Test
        @DisplayName("say so when nothing later applies them")
        void exclusionGoesNowhere() {
            Plan plan = plan(CUTOVER.replace("""
                        - id: policies
                          copyTopology:
                            from: blue
                            to: green
                            include: [policies, operatorPolicies]
                    """, ""));
            assertThat(step(plan, "topology").get(1))
                    .isEqualTo("policies, operatorPolicies EXCLUDED — and no later step applies them");
            assertThat(plan.warnings()).anyMatch(warning -> warning.contains("no later step"));
        }

        @Test
        @DisplayName("count the connections a close would actually close")
        void closeCounts() {
            assertThat(step(plan(CUTOVER), "drain-consumers").get(0)).isEqualTo(
                    "close 8 consuming connections on blue (orders-service), after unacked=0");
        }

        @Test
        @DisplayName("count every connection when the selector names no role")
        void closeWithoutRole() {
            Plan plan = plan(CUTOVER.replace("select: { users: [orders-service], role: consumer }",
                    "select: { users: [orders-service] }"));
            assertThat(step(plan, "drain-consumers").get(0))
                    .startsWith("close 9 connections on blue (orders-service)");
        }

        @Test
        @DisplayName("count the queues and the messages a shovel would move")
        void drainCounts() {
            assertThat(step(plan(CUTOVER), "drain-messages")).containsExactly(
                    "shovel blue → green, 30 queues (orders.audit excluded)",
                    "27,412 messages to move",
                    "wait: blue depth=0, 15m, on timeout ABORT");
        }

        @Test
        @DisplayName("print a guard's condition, its timeout and what the timeout means")
        void guards() {
            assertThat(step(plan(CUTOVER), "pause-producers"))
                    .containsExactly("wait: blue publishRate=0, 2m, on timeout PROMPT");
            assertThat(step(plan(CUTOVER), "verify"))
                    .containsExactly("wait: green consumers>=1, 5m, on timeout ABORT");
        }

        @Test
        @DisplayName("mark an unwritten timeout decision as the default rather than omitting it")
        void guardDefault() {
            Plan plan = plan(CUTOVER.replace(", onTimeout: prompt", ""));
            assertThat(step(plan, "pause-producers"))
                    .containsExactly("wait: blue publishRate=0, 2m, on timeout ABORT (default)");
        }

        @Test
        @DisplayName("say what the human is being asked to do at an external endpoint")
        void externalEndpoint() {
            assertThat(step(plan(CUTOVER), "switch-endpoint")).containsExactly(
                    "EXTERNAL — will stop and wait:",
                    "\"switched by the platform team\"");
        }

        @Test
        @DisplayName("print a hook's command with the target substituted in")
        void hookEndpoint() {
            DeploymentFile file = Fixtures.parse("""
                    apiVersion: acemq.org/v1alpha1
                    kind: Deployment
                    metadata:
                      name: hooked
                    provider: rabbitmq
                    clusters:
                      blue:
                        management: https://blue.internal:15671
                        username: u
                        password: p
                      green:
                        management: https://green.internal:15671
                        username: u
                        password: p
                    endpoint:
                      kind: hook
                      run: ./switch-endpoint.sh
                      args: ["{{target}}"]
                      timeout: 2m
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: switch-endpoint
                          endpoint: { target: green }
                    """);
            Plan plan = Planner.plan(file, Probes.blue().build(), Probes.green().build());
            assertThat(step(plan, "switch-endpoint"))
                    .containsExactly("HOOK — run ./switch-endpoint.sh green", "wait 2m");
        }

        @Test
        @DisplayName("describe a mirror as a copy, in those words")
        void mirrorIsACopy() {
            Plan plan = plan("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: [orders]
                      steps:
                        - id: mirror
                          mirror: { from: blue, to: green, exchanges: [orders] }
                    """);
            assertThat(step(plan, "mirror")).containsExactly(
                    "federate 1 exchange blue → green (orders)",
                    "messages are COPIED — blue keeps them",
                    "EXCHANGE federation, never queue federation — a federated queue pulls only"
                            + " when the upstream has no local consumers, which is a conditional"
                            + " move");
        }
    }

    @Nested
    @DisplayName("capabilities")
    class Capabilities {

        @Test
        @DisplayName("refuse the plan, naming the step, the cluster and the reason")
        void refusesWhenAStepCannotRun() {
            Plan plan = plan(CUTOVER, Probes.blue()
                    .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled").build(),
                    Probes.green().build());
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal -> refusal.contains("drain-messages")
                    && refusal.contains("DRAIN_BY_SHOVEL")
                    && refusal.contains("on blue")
                    && refusal.contains("rabbitmq_shovel is not enabled"));
        }

        @Test
        @DisplayName("are checked against the cluster the step names, not against either cluster")
        void againstTheRightCluster() {
            // The target cannot close connections. The close step is on the source, so that is not
            // this plan's problem -- and a check that folded the two clusters into one set would
            // report it as one.
            Plan plan = plan(CUTOVER, Probes.blue().build(), Probes.green()
                    .cannot(Capability.CONNECTION_CLOSE, "the user is monitoring-only").build());
            assertThat(plan.refusals()).noneMatch(refusal -> refusal.contains("CONNECTION_CLOSE"));
        }

        @Test
        @DisplayName("hold the file's requires: list against both clusters, because of the rollback")
        void requiresBothEnds() {
            Plan plan = plan(CUTOVER, Probes.blue().build(), Probes.green()
                    .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled").build());
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal -> refusal.startsWith("the file requires")
                    && refusal.contains("green does not have it"));
        }

        @Test
        @DisplayName("are reported once, at the step, rather than twice")
        void notReportedTwice() {
            Plan plan = plan(CUTOVER, Probes.blue()
                    .cannot(Capability.DRAIN_BY_SHOVEL, "rabbitmq_shovel is not enabled").build(),
                    Probes.green().build());
            assertThat(plan.refusals()).filteredOn(refusal -> refusal.contains("DRAIN_BY_SHOVEL"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("include the operator policy a second topology copy needs")
        void operatorPolicyIsNeeded() {
            Plan plan = plan(CUTOVER, Probes.blue().build(), Probes.green()
                    .cannot(Capability.OPERATOR_POLICY, "RabbitMQ 3.6 has no operator policies")
                    .build());
            assertThat(plan.refusals()).anyMatch(refusal -> refusal.contains("policies")
                    && refusal.contains("OPERATOR_POLICY") && refusal.contains("on green"));
        }

        @Test
        @DisplayName("refuse a step naming a cluster nobody probed")
        void unprobedCluster() {
            Plan plan = plan("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: third-party
                          copyTopology: { from: blue, to: amber }
                    """);
            assertThat(plan.refusals())
                    .anyMatch(refusal -> refusal.contains("'amber'")
                            && refusal.contains("was not probed"));
        }
    }

    @Nested
    @DisplayName("warnings")
    class Warnings {

        @Test
        @DisplayName("name the streams in scope and what happens to their consumers")
        void streams() {
            Plan plan = plan(CUTOVER);
            assertThat(plan.warnings().get(0))
                    .contains("2 streams in scope (orders.events, orders.events.raw)")
                    .contains("Offsets do not travel between clusters");
            assertThat(plan.refusals())
                    .anyMatch(refusal -> refusal.contains("streams.acknowledged is not set"));
        }

        /**
         * The half of stream handling docs/roadmap.md asks for by name.
         *
         * <p>Three consumers on one stream, configured three different ways, and the plan says
         * three different things about them. A single warning about offsets not travelling is true
         * of all three and answers the question about none of them: the operator is signing off on
         * a week of reprocessing for one and a silent gap for the next.
         */
        @Test
        @DisplayName("project what each stream consumer will do, one at a time")
        void perConsumerProjection() {
            Plan plan = plan("streams:\n  acknowledged: true\n" + CUTOVER);
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("orders.events · audit-writer")
                            && warning.contains("`first`")
                            && warning.contains("replays the target's entire retained log"));
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("orders.events · ledger-tailer")
                            && warning.contains("`next`")
                            && warning.contains("is never delivered"));
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("orders.events · search-indexer")
                            && warning.contains("no x-stream-offset, which RabbitMQ reads as"
                                    + " `next`"));
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("orders.events.raw · audit-writer")
                            && warning.contains("a timestamp or an interval"));
        }

        @Test
        @DisplayName("refuse the plan until the file acknowledges what a stream will do")
        void streamsMustBeAcknowledged() {
            assertThat(plan(CUTOVER).ok()).isFalse();
            Plan acknowledged = plan("streams:\n  acknowledged: true\n" + CUTOVER);
            assertThat(acknowledged.ok()).isTrue();
            assertThat(acknowledged.warnings())
                    .anyMatch(warning -> warning.contains("streams.acknowledged is set, so the"
                            + " plan proceeds"));
        }

        /**
         * A confirmation about the wrong consequence is not a confirmation.
         *
         * <p>{@code restartAt} sets nothing — nothing in this tool can move an offset — so its only
         * job is to be checked against the consumers. A file that says the consumers will skip the
         * window, over an estate whose consumers replay the whole log, has signed off on the
         * opposite of what will happen.
         */
        @Test
        @DisplayName("refuse when streams.restartAt and the live consumers disagree")
        void restartAtMustMatchTheConsumers() {
            Plan plan = plan("streams:\n  acknowledged: true\n  restartAt: next\n" + CUTOVER);
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("streams.restartAt is not what the consumers are asking for")
                            && refusal.contains("audit-writer on orders.events asks for first"
                                    + " where the file says next"));
        }

        @Test
        @DisplayName("accept streams.restartAt when every consumer is asking for it")
        void restartAtThatMatches() {
            ProbedCluster agreed = Probes.blue()
                    .inventory(Inventory.counting()
                            .queue("orders.new", "classic", 12, 1)
                            .queue("orders.events", "stream", 0, 2)
                            .streamConsumer("orders.events", "10.0.0.9:51000", "audit-writer",
                                    "next")
                            // Nothing written at all, which RabbitMQ reads as `next` -- so this
                            // consumer agrees with the file without saying so, and a comparison
                            // that only looked at the text would refuse a correct file.
                            .consumer("orders.events", "10.0.0.9:51001", "ledger-tailer")
                            .build())
                    .build();
            Plan plan = plan("streams:\n  acknowledged: true\n  restartAt: next\n" + CUTOVER,
                    agreed, Probes.green().build());
            assertThat(plan.refusals()).isEmpty();
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("every consumer asks for next"));
        }

        /**
         * Two streams that owe two different answers, which is the estate a scalar cannot describe.
         *
         * <p>{@code orders.events} is consumed by something that skips the window and
         * {@code orders.ledger} by something that replays the whole log, and both are true at once.
         * No single value is a correct statement about this estate, so before the mapping every
         * honest file for it was refused and the only way to run the tool was to stop running it.
         */
        @Test
        @DisplayName("accept a restartAt written one stream at a time")
        void restartAtPerStream() {
            Plan plan = plan("""
                    streams:
                      acknowledged: true
                      restartAt:
                        orders.events: next
                        orders.ledger: first
                    """ + CUTOVER, disagreeingStreams(), Probes.green().build());
            assertThat(plan.refusals()).isEmpty();
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("every consumer asks for what streams.restartAt says for its"
                            + " stream"));
        }

        /**
         * The same estate, with the file wrong about one of the two.
         */
        @Test
        @DisplayName("refuse the one stream the per-stream restartAt is wrong about")
        void restartAtPerStreamThatIsWrongAboutOne() {
            Plan plan = plan("""
                    streams:
                      acknowledged: true
                      restartAt:
                        orders.events: next
                        orders.ledger: next
                    """ + CUTOVER, disagreeingStreams(), Probes.green().build());
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("audit-writer on orders.ledger asks for first where the file"
                            + " says next")
                            && !refusal.contains("on orders.events"));
        }

        /**
         * A mapping is allowed to be incomplete, so the incomplete one has to be the refusal.
         *
         * <p>A stream in scope with no line is the exact failure the confirmation exists to
         * prevent: falling back to anything at all would let {@code acknowledged: true} sign for a
         * consequence the file never described, and it would do it while looking like a finished
         * file rather than a half-written one.
         */
        @Test
        @DisplayName("refuse a per-stream restartAt that leaves a stream in scope unnamed")
        void restartAtPerStreamWithAGap() {
            Plan plan = plan("""
                    streams:
                      acknowledged: true
                      restartAt:
                        orders.events: next
                    """ + CUTOVER, disagreeingStreams(), Probes.green().build());
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("says nothing about orders.ledger")
                            && refusal.contains("Name every stream in scope"));
        }

        /**
         * The line that is checked against nothing, which is worth a sentence and not a refusal.
         */
        @Test
        @DisplayName("say when restartAt names a stream the drain is not moving")
        void restartAtNamingAStreamOutOfScope() {
            Plan plan = plan("""
                    streams:
                      acknowledged: true
                      restartAt:
                        orders.events: next
                        orders.ledger: first
                        warehouse.events: first
                    """ + CUTOVER, disagreeingStreams(), Probes.green().build());
            assertThat(plan.refusals()).isEmpty();
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("streams.restartAt names warehouse.events, which is not a"
                            + " stream in the drain's scope on blue"));
        }

        /** Two streams in the drain's scope whose consumers want opposite things. */
        private static ProbedCluster disagreeingStreams() {
            return Probes.blue()
                    .inventory(Inventory.counting()
                            .queue("orders.new", "classic", 12, 1)
                            .queue("orders.events", "stream", 0, 2)
                            .queue("orders.ledger", "stream", 0, 1)
                            .streamConsumer("orders.events", "10.0.0.9:51000", "search-indexer",
                                    "next")
                            .consumer("orders.events", "10.0.0.9:51001", "ledger-tailer")
                            .streamConsumer("orders.ledger", "10.0.0.9:51002", "audit-writer",
                                    "first")
                            .build())
                    .build();
        }

        /**
         * The estate where the projection cannot be made, which is not the estate where it is fine.
         */
        @Test
        @DisplayName("refuse a stream when the consumers could not be listed at all")
        void streamsWithNoConsumerListing() {
            ProbedCluster blind = Probes.blue()
                    .inventory(Inventory.counting()
                            .queue("orders.events", "stream", 0, 2)
                            .consumersUnreadable("these credentials cannot read /api/consumers")
                            .build())
                    .build();
            Plan plan = plan("streams:\n  acknowledged: true\n" + CUTOVER, blind,
                    Probes.green().build());
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("could not be listed")
                            && refusal.contains("streams.acknowledged confirms a consequence"));
        }

        @Test
        @DisplayName("say nothing about streams when there are none in the drain's scope")
        void noStreams() {
            ProbedCluster withoutStreams = Probes.blue()
                    .inventory(Inventory.counting().queue("orders.new", "classic", 12, 1).build())
                    .build();
            Plan plan = plan(CUTOVER, withoutStreams, Probes.green().build());
            assertThat(plan.warnings()).noneMatch(warning -> warning.contains("stream"));
            assertThat(plan.ok()).isTrue();
        }

        @Test
        @DisplayName("state what a shovel does to every message it republishes")
        void republishing() {
            assertThat(plan(CUTOVER).warnings()).anyMatch(warning ->
                    warning.contains("x-delivery-count resets and x-death is erased")
                            && warning.contains("27,412 messages"));
        }

        @Test
        @DisplayName("say which way the rollback drains and when the source goes empty")
        void rollback() {
            Plan plan = plan(CUTOVER + """
                    rollback:
                      keep: blue
                      for: 72h
                      steps:
                        - id: switch-endpoint
                          endpoint: { target: blue }
                        - id: drain-back
                          drain: { from: green, to: blue, queues: ["orders.*"] }
                          waitFor: { on: green, depth: 0, timeout: 15m, onTimeout: abort }
                    """);
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("rollback drains green → blue")
                            && warning.contains("blue's queues will be empty after step 6")
                            && warning.contains("republished twice"));
        }

        @Test
        @DisplayName("say that a stranded message is the choice atMostOnce made")
        void atMostOnce() {
            Plan plan = plan(CUTOVER.replace("atLeastOnce", "atMostOnce"));
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("a message may be stranded on blue"));
        }

        @Test
        @DisplayName("say that a mirror's consumers must be in shadow mode")
        void shadowMode() {
            Plan plan = plan("""
                    deployment:
                      operation: mirror
                      from: blue
                      to: green
                      mirror:
                        exchanges: [orders]
                      steps:
                        - id: mirror
                          mirror: { from: blue, to: green, exchanges: [orders] }
                    """);
            assertThat(plan.warnings())
                    .anyMatch(warning -> warning.contains("shadow mode"));
        }

        /**
         * The row docs/canary.md ends on, and it is the first warning rather than the last.
         *
         * <p>A canary depends on one service resolving to the target while everything else still
         * resolves to the source. The page says the tool should put that in the plan rather than
         * let somebody discover it at step nine, and "first" is how a warning list says "before
         * you read the rest of this".
         */
        @Test
        @DisplayName("put per-service routing at the top of a canary's warnings")
        void canaryNeedsPerServiceRouting() {
            Plan plan = plan(Scope.CANARY, Scope.canaryBlue(), Probes.green().build());
            assertThat(plan.warnings().get(0))
                    .contains("PER-SERVICE routing")
                    .contains("notification-service must resolve to green")
                    .contains("everything else still resolves to blue")
                    .contains("endpoint.kind is external");
        }
    }

    @Nested
    @DisplayName("a canary's scope")
    class Scope {

        @Test
        @DisplayName("resolve to the queues it names and say who is attached")
        void resolvesAndCounts() {
            Plan plan = plan(CANARY, canaryBlue(), Probes.green().build());
            assertThat(plan.ok()).isTrue();
            assertThat(plan.scope()).containsExactly(
                    "2 queues in scope on blue: orders.notifications, orders.notifications.dlq",
                    "2 consumers attached to them, scope.services names notification-service");
            assertThat(plan.render()).contains("  scope             2 queues in scope on blue");
        }

        /**
         * The documented refusal, and the reason the operation is safe at all.
         */
        @Test
        @DisplayName("refuse when something the file did not name is consuming a scoped queue")
        void refusesAnUnlistedConsumer() {
            ProbedCluster blue = canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 2)
                    .consumer("orders.notifications", "10.0.0.5:51000", "notification-service")
                    // The one nobody remembered. Moving the queue without it partitions it.
                    .consumer("orders.notifications", "10.0.0.9:51044", "legacy-mailer")
                    .build());
            Plan plan = plan(CANARY, blue, Probes.green().build());

            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("'legacy-mailer' is consuming orders.notifications on blue")
                            && refusal.contains("10.0.0.9:51044")
                            && refusal.contains("partitions it"));
        }

        /**
         * The half docs/canary.md does not state, which fails more quietly than the half it does.
         */
        @Test
        @DisplayName("refuse when a named service also consumes a queue the scope left out")
        void refusesAServiceThatReachesOutsideTheScope() {
            ProbedCluster blue = canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 1)
                    .queue("orders.new", "classic", 900, 1)
                    .consumer("orders.notifications", "10.0.0.5:51000", "notification-service")
                    .consumer("orders.new", "10.0.0.5:51000", "notification-service")
                    .build());
            Plan plan = plan(CANARY, blue, Probes.green().build());

            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("'notification-service' is named in deployment.scope.services")
                            && refusal.contains("orders.new")
                            && refusal.contains("nothing consuming them"));
        }

        /**
         * The estate where the check cannot be made, which is not the estate where it passes.
         */
        @Test
        @DisplayName("refuse when the consumers could not be listed at all")
        void refusesWhenItCannotTell() {
            ProbedCluster blue = canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 3)
                    .consumersUnreadable("these credentials cannot read /api/consumers on blue")
                    .build());
            Plan plan = plan(CANARY, blue, Probes.green().build());

            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("the consumers of blue could not be listed")
                            && refusal.contains("the one check that makes a canary safe"));
            assertThat(plan.scope()).anyMatch(line -> line.contains("UNREADABLE"));
        }

        @Test
        @DisplayName("warn when a named service is consuming nothing the scope selected")
        void warnsAboutAServiceNobodyCanFind() {
            ProbedCluster blue = canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 0)
                    .build());
            Plan plan = plan(CANARY, blue, Probes.green().build());

            assertThat(plan.ok()).isTrue();
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("names notification-service and nothing by that name is"
                            + " consuming any queue in scope"));
            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("nothing is consuming orders.notifications"));
        }

        @Test
        @DisplayName("warn when the scope names a queue the cluster has not got")
        void warnsAboutAQueueThatIsNotThere() {
            ProbedCluster blue = canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 1)
                    .consumer("orders.notifications", "10.0.0.5:51000", "notification-service")
                    .build());
            Plan plan = plan(CANARY, blue, Probes.green().build());

            assertThat(plan.warnings()).anyMatch(warning ->
                    warning.contains("names orders.notifications.dlq")
                            && warning.contains("blue has no queue by that name"));
        }

        @Test
        @DisplayName("refuse a canary with no scope block at all")
        void refusesACanaryWithNoScope() {
            Plan plan = plan("""
                    deployment:
                      operation: canary
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: verify
                          waitFor: { on: green, consumers: { min: 1 }, timeout: 5m }
                    """);
            assertThat(plan.ok()).isFalse();
            assertThat(plan.refusals()).anyMatch(refusal ->
                    refusal.contains("there is no deployment.scope block")
                            && refusal.contains("nothing smaller about it"));
        }

        @Test
        @DisplayName("stay out of a blue/green plan, which has no scope to print")
        void aWholeEstateCutoverHasNoScopeBlock() {
            assertThat(plan("streams:\n  acknowledged: true\n" + CUTOVER).scope()).isEmpty();
        }

        private static final String CANARY = """
                deployment:
                  operation: canary
                  from: blue
                  to: green
                  semantics: atLeastOnce
                  scope:
                    vhost: /orders
                    queues: [orders.notifications, orders.notifications.dlq]
                    services: [notification-service]
                  steps:
                    - id: verify
                      waitFor: { on: green, consumers: { min: 1 }, timeout: 5m }
                """;

        private static ProbedCluster canaryBlue() {
            return canary(Inventory.counting()
                    .queue("orders.notifications", "classic", 40, 1)
                    .queue("orders.notifications.dlq", "classic", 2, 1)
                    .consumer("orders.notifications", "10.0.0.5:51000", "notification-service")
                    .consumer("orders.notifications.dlq", "10.0.0.5:51001",
                            "notification-service")
                    .build());
        }

        private static ProbedCluster canary(Inventory inventory) {
            return Probes.blue().inventory(inventory).build();
        }
    }

    @Nested
    @DisplayName("a file with no steps")
    class Defaulted {

        private static final String BARE = """
                streams:
                  acknowledged: true
                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                """;

        @Test
        @DisplayName("is planned against the default list for its operation")
        void fillsInTheDefault() {
            assertThat(plan(BARE).steps()).extracting(PlannedStep::id).containsExactly("backup",
                    "topology", "announce-drain", "pause-producers", "drain-consumers",
                    "drain-messages", "policies", "switch-endpoint", "verify");
        }

        @Test
        @DisplayName("is told that the list was filled in and can be pinned")
        void saysSo() {
            assertThat(plan(BARE).warnings())
                    .anyMatch(warning -> warning.contains("no steps: list"));
        }

        @Test
        @DisplayName("still splits the topology copy around the drain")
        void keepsTheSplit() {
            Plan plan = plan(BARE);
            assertThat(step(plan, "topology")).anyMatch(line ->
                    line.equals("policies, operatorPolicies EXCLUDED — applied at step "
                            + number(plan, "policies")));
            assertThat(number(plan, "drain-messages")).isLessThan(number(plan, "policies"));
        }

        @Test
        @DisplayName("says there is nothing to announce when the file has no envelope")
        void noAnnounceBlock() {
            assertThat(step(plan(BARE), "announce-drain"))
                    .containsExactly("deployment.announce is not set — nothing is published");
        }
    }

    @Nested
    @DisplayName("the plan itself")
    class TheArtifact {

        @Test
        @DisplayName("ends by saying that nothing was written, and what would write")
        void closingLine() {
            assertThat(plan("streams:\n  acknowledged: true\n" + CUTOVER).render())
                    .endsWith("nothing was written. run `acemq-infra apply -f` on this file to"
                            + " execute it, or `apply --dry-run` to see what each step would do"
                            + " right now.\n");
        }

        @Test
        @DisplayName("says nothing was written even when it refuses")
        void closingLineOnRefusal() {
            assertThat(plan(CUTOVER).render()).endsWith(
                    "nothing was written, and nothing would be: this plan is refused.\n");
        }

        @Test
        @DisplayName("renders the same text every time, so two plans can be diffed")
        void deterministic() {
            assertThat(plan(CUTOVER).render()).isEqualTo(plan(CUTOVER).render());
        }

        @Test
        @DisplayName("leaves the backup's timestamp unresolved, for the same reason")
        void noClock() {
            assertThat(plan(CUTOVER).render()).contains("{{timestamp}}");
        }

        @Test
        @DisplayName("refuses to plan a file with no deployment block rather than guessing")
        void needsADeployment() {
            DeploymentFile file = Fixtures.parse(Fixtures.around(""));
            assertThatThrownBy(() ->
                    Planner.plan(file, Probes.blue().build(), Probes.green().build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Validate it first");
        }

        /**
         * The exhaustiveness the sealed {@code Action} type was for.
         *
         * <p>Pattern matching for {@code switch} is not final until Java 21 and this module
         * compiles at 17, so the planner dispatches on a chain of {@code instanceof} and the
         * compiler cannot say when one is missing. This test says it instead: an eighth action
         * added to the sealed set arrives here with no branch and fails.
         */
        @Test
        @DisplayName("has an answer for every action a step can carry")
        void everyActionIsHandled() {
            // One step per action, so that a missing branch in the planner reaches the throw at
            // the bottom of its dispatch rather than being passed over.
            Plan plan = plan("""
                    deployment:
                      operation: blueGreen
                      from: blue
                      to: green
                      semantics: atLeastOnce
                      steps:
                        - id: requires
                          requires: [TOPOLOGY_EXPORT]
                          # a second action keeps this from being read as the probe summary
                          announce: {}
                        - id: copy
                          copyTopology: { from: blue, to: green }
                        - id: announce
                          announce: {}
                        - id: close
                          closeConnections: { on: blue }
                        - id: drain
                          drain: { from: blue, to: green }
                        - id: mirror
                          mirror: { from: blue, to: green, exchanges: [orders] }
                        - id: switch
                          endpoint: { target: green }
                    """);
            assertThat(plan.steps()).hasSize(8);

            List<String> handled = List.of("Requires", "CopyTopology", "Announce",
                    "CloseConnections", "Drain", "Mirror", "Switch");
            assertThat(Action.class.getPermittedSubclasses())
                    .extracting(Class::getSimpleName)
                    .containsExactlyInAnyOrderElementsOf(handled);
        }
    }
}
