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
package org.acemq.infra.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

import org.acemq.infra.execute.Journal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The state machine against an API server that keeps what it is given, and an engine that
 * records what it was asked to do instead of touching a broker.
 */
@EnableKubernetesMockClient(crud = true)
class CutoverReconcilerTest {

    private static final String NS = "estate";

    private static final String DEPLOYMENT = "kind: Deployment\nmetadata: {name: orders}\n";

    private static final String RUNNING_AT_DRAIN = """
            {"format":1,"outcome":"running","steps":[
              {"number":1,"index":0,"id":"topology","status":"done"},
              {"number":2,"index":1,"id":"drain-messages","status":"started"}]}""";

    KubernetesClient client;
    KubernetesMockServer server;

    private Recorder engine;
    private CutoverReconciler reconciler;

    @BeforeEach
    void setUp() {
        engine = new Recorder();
        reconciler = new CutoverReconciler(client, engine, Allowlist.parse(null));
    }

    @Test
    @DisplayName("plans into status and applies nothing until the approval names that plan")
    void planThenApprove() {
        create("orders");
        reconcile("orders");
        Cutover.Status planned = status("orders");
        assertThat(planned.phase).isEqualTo(Cutover.PLANNED);
        assertThat(planned.planFingerprint).isEqualTo("plan-1");
        assertThat(planned.plan).isEqualTo("the plan text\n");
        assertThat(planned.message).contains("spec.approve: plan-1");
        assertThat(engine.applies).isEmpty();

        approve("orders", "plan-1");
        reconcile("orders");
        Cutover.Status ran = status("orders");
        assertThat(engine.applies).hasSize(1);
        assertThat(ran.phase).isEqualTo(Cutover.COMPLETED);
        assertThat(ran.journalOutcome).isEqualTo("completed");
        assertThat(ran.journal).isEqualTo("orders-journal");
        assertThat(journal("orders")).contains("\"completed\"");
        assertThat(ran.steps).containsExactly("1 topology done", "2 drain-messages done");

        // Done is done: another pass, with the same approval still there, runs nothing.
        reconcile("orders");
        assertThat(engine.applies).hasSize(1);
    }

    @Test
    @DisplayName("refuses an approval of a plan that has since changed, and applies nothing")
    void staleApproval() {
        create("orders");
        reconcile("orders");
        approve("orders", "plan-1");
        engine.fingerprint = "plan-2";
        reconcile("orders");

        Cutover.Status status = status("orders");
        assertThat(status.phase).isEqualTo(Cutover.PLANNED);
        assertThat(status.planFingerprint).isEqualTo("plan-2");
        assertThat(status.message).contains("names plan plan-1").contains("now plan-2");
        assertThat(engine.applies).isEmpty();
    }

    @Test
    @DisplayName("a dry run plans and never applies, approved or not")
    void dryRun() {
        Cutover cutover = cutover("orders");
        cutover.getSpec().dryRun = true;
        cutover.getSpec().approve = "plan-1";
        client.resource(cutover).create();
        reconcile("orders");
        assertThat(status("orders").phase).isEqualTo(Cutover.PLANNED);
        assertThat(status("orders").message).contains("dry run");
        assertThat(engine.applies).isEmpty();
    }

    @Test
    @DisplayName("found Applying after a restart: Interrupted, the last started step named, nothing"
            + " re-run")
    void restartIsInterrupted() {
        create("orders");
        approve("orders", "plan-1");
        setPhase("orders", Cutover.APPLYING);
        storeJournal("orders", RUNNING_AT_DRAIN);

        reconcile("orders");
        Cutover.Status status = status("orders");
        assertThat(status.phase).isEqualTo(Cutover.INTERRUPTED);
        assertThat(status.journalOutcome).isEqualTo("running");
        assertThat(status.lastStep).isEqualTo("2 drain-messages started");
        assertThat(status.message).contains("Nothing is resumed");
        assertThat(engine.plans).isZero();
        assertThat(engine.applies).isEmpty();
        assertThat(journal("orders")).isEqualTo(RUNNING_AT_DRAIN);

        // And it stays that way, however often it is reconciled.
        reconcile("orders");
        reconcile("orders");
        assertThat(engine.applies).isEmpty();
        assertThat(status("orders").phase).isEqualTo(Cutover.INTERRUPTED);
    }

    @Test
    @DisplayName("a journal with no status to explain it is never run over, even when approved")
    void lostStatus() {
        create("orders");
        approve("orders", "plan-1");
        storeJournal("orders", RUNNING_AT_DRAIN);
        reconcile("orders");
        assertThat(status("orders").phase).isEqualTo(Cutover.INTERRUPTED);
        assertThat(engine.applies).isEmpty();
    }

    @Test
    @DisplayName("rolls back from an Interrupted run through the journal the ConfigMap holds")
    void rollbackFromInterrupted() {
        create("orders");
        setPhase("orders", Cutover.APPLYING);
        storeJournal("orders", RUNNING_AT_DRAIN);
        reconcile("orders");

        action("orders", Cutover.ROLLBACK);
        reconcile("orders");
        assertThat(engine.rollbacks).containsExactly(RUNNING_AT_DRAIN);
        Cutover.Status status = status("orders");
        assertThat(status.phase).isEqualTo(Cutover.ROLLED_BACK);
        assertThat(status.rollbackOutcome).isEqualTo("completed");
        assertThat(journal("orders")).contains("\"rollback\"");

        reconcile("orders");
        assertThat(engine.rollbacks).hasSize(1);
    }

    @Test
    @DisplayName("a rollback refused before its first write leaves the phase and is asked again")
    void refusedRollback() {
        create("orders");
        reconcile("orders");
        approve("orders", "plan-1");
        reconcile("orders");
        engine.refuseRollback = true;
        action("orders", Cutover.ROLLBACK);
        var control = reconciler.handle(fetch("orders"));

        assertThat(status("orders").phase).isEqualTo(Cutover.COMPLETED);
        assertThat(status("orders").message).contains("refused").contains("drain is still running");
        assertThat(control.getScheduleDelay()).isPresent();
    }

    @Test
    @DisplayName("found RollingBack with the journal marked: RollbackInterrupted, never a second")
    void rollbackInterrupted() {
        create("orders");
        action("orders", Cutover.ROLLBACK);
        setPhase("orders", Cutover.ROLLING_BACK);
        storeJournal("orders", """
                {"format":1,"outcome":"completed","steps":[],
                 "rollback":{"outcome":"running","steps":[]}}""");
        reconcile("orders");
        assertThat(status("orders").phase).isEqualTo(Cutover.ROLLBACK_INTERRUPTED);
        assertThat(engine.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("reads variables from Secrets in the resource's namespace and never shows them")
    void secrets() {
        client.resource(new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("blue-default-user")
                        .withNamespace(NS)
                        .addToLabels(CutoverReconciler.CREDENTIALS, "true").build())
                .addToData("password", Base64.getEncoder()
                        .encodeToString("s3cr3t".getBytes(StandardCharsets.UTF_8)))
                .build()).create();
        Cutover cutover = cutover("orders");
        cutover.getSpec().variables = List.of(variable("BLUE_PASSWORD", "blue-default-user",
                "password"), literal("BLUE_URL", "http://blue:15672"));
        client.resource(cutover).create();
        reconcile("orders");

        assertThat(engine.env).containsEntry("BLUE_PASSWORD", "s3cr3t")
                .containsEntry("BLUE_URL", "http://blue:15672");
        assertThat(engine.sources).contains("BLUE_PASSWORD=secret:blue-default-user/password")
                .doesNotContain("s3cr3t");

        Cutover missing = cutover("other");
        missing.getSpec().variables = List.of(variable("GREEN_PASSWORD", "green-default-user",
                "password"));
        client.resource(missing).create();
        reconcile("other");
        Cutover.Status status = status("other");
        assertThat(status.phase).isEqualTo(Cutover.REFUSED);
        assertThat(status.message).contains("green-default-user").contains("no such Secret");
    }

    @Test
    @DisplayName("deleting a Cutover whose run was not rolled back keeps its journal, detached and"
            + " labelled, and does the same however often it is asked")
    void deletionRetainsTheJournal() {
        complete("orders");
        String uid = fetch("orders").getMetadata().getUid();
        assertThat(configMap("orders-journal").getMetadata().getOwnerReferences())
                .extracting(r -> r.getUid()).containsExactly(uid);

        for (int pass = 0; pass < 2; pass++) {
            assertThat(reconciler.cleanup(fetch("orders"), null).isRemoveFinalizer()).isTrue();
            ConfigMap kept = configMap("orders-journal");
            assertThat(kept.getMetadata().getOwnerReferences()).isEmpty();
            assertThat(kept.getMetadata().getLabels())
                    .containsEntry(CutoverReconciler.RETAINED, "true")
                    .containsEntry(CutoverReconciler.CUTOVER, "orders")
                    .containsEntry(CutoverReconciler.CUTOVER_UID, uid);
            assertThat(kept.getMetadata().getAnnotations().get(CutoverReconciler.RETAINED_REASON))
                    .contains("orders").contains("completed").contains("not rolled back");
            assertThat(kept.getData()).containsEntry(CutoverReconciler.DEPLOYMENT_KEY, DEPLOYMENT)
                    .containsKey(CutoverReconciler.JOURNAL_KEY);
        }
    }

    @Test
    @DisplayName("deleting a Cutover that never ran, or was rolled back, lets its journal go")
    void deletionLetsGo() {
        create("planned");
        reconcile("planned");
        assertThat(reconciler.cleanup(fetch("planned"), null).isRemoveFinalizer()).isTrue();
        assertThat(client.configMaps().inNamespace(NS).list().getItems()).isEmpty();

        complete("undone");
        action("undone", Cutover.ROLLBACK);
        reconcile("undone");
        assertThat(status("undone").phase).isEqualTo(Cutover.ROLLED_BACK);
        assertThat(reconciler.cleanup(fetch("undone"), null).isRemoveFinalizer()).isTrue();
        ConfigMap owned = configMap("undone-journal");
        assertThat(owned.getMetadata().getOwnerReferences()).hasSize(1);
        assertThat(owned.getMetadata().getLabels()).doesNotContainKey(CutoverReconciler.RETAINED);
    }

    @Test
    @DisplayName("a new Cutover rolls back from a retained journal, takes it, and only once")
    void rollbackFromRetained() {
        complete("orders");
        reconciler.cleanup(fetch("orders"), null);
        client.resources(Cutover.class).inNamespace(NS).withName("orders").delete();
        String journal = journal("orders");
        int plans = engine.plans;

        undo("undo", "orders-journal");
        reconcile("undo");
        Cutover.Status status = status("undo");
        assertThat(engine.rollbacks).containsExactly(journal);
        assertThat(engine.files).containsExactly(DEPLOYMENT);
        assertThat(status.phase).isEqualTo(Cutover.ROLLED_BACK);
        assertThat(status.journal).isEqualTo("orders-journal");
        assertThat(status.rollbackOutcome).isEqualTo("completed");
        assertThat(journal("orders")).contains("\"rollback\"");
        assertThat(configMap("orders-journal").getMetadata().getOwnerReferences())
                .extracting(r -> r.getUid()).containsExactly(fetch("undo").getMetadata().getUid());
        assertThat(engine.plans).isEqualTo(plans);
        assertThat(engine.applies).hasSize(1);

        reconcile("undo");
        assertThat(engine.rollbacks).hasSize(1);
    }

    @Test
    @DisplayName("journalFrom refuses a ConfigMap that is not a retained journal, one another"
            + " Cutover has taken, and anything but a rollback")
    void journalFromRefusals() {
        storeJournal("stray", RUNNING_AT_DRAIN);
        undo("a", "stray-journal");
        reconcile("a");
        assertThat(status("a").phase).isEqualTo(Cutover.REFUSED);
        assertThat(status("a").message).contains(CutoverReconciler.RETAINED);

        complete("orders");
        reconciler.cleanup(fetch("orders"), null);
        client.resources(Cutover.class).inNamespace(NS).withName("orders").delete();
        engine.refuseRollback = true;
        undo("first", "orders-journal");
        reconcile("first");
        undo("second", "orders-journal");
        reconcile("second");
        assertThat(status("second").phase).isEqualTo(Cutover.REFUSED);
        assertThat(status("second").message).contains("first");

        Cutover planning = cutover("planning");
        planning.getSpec().journalFrom = journalFrom("orders-journal");
        client.resource(planning).create();
        int plans = engine.plans;
        reconcile("planning");
        assertThat(status("planning").phase).isEqualTo(Cutover.REFUSED);
        assertThat(status("planning").message).contains("action: rollback");
        assertThat(engine.plans).isEqualTo(plans);
        assertThat(engine.rollbacks).isEmpty();
    }

    @Test
    @DisplayName("a Secret without the credentials label is refused, by name and label, and only"
            + " its metadata is ever asked for")
    void unlabelledSecret() throws InterruptedException {
        client.resource(new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("plain").withNamespace(NS)
                        .addToLabels(CutoverReconciler.CREDENTIALS, "yes").build())
                .addToData("password", Base64.getEncoder()
                        .encodeToString("s3cr3t".getBytes(StandardCharsets.UTF_8)))
                .build()).create();
        int before = server.getRequestCount();
        for (int i = 0; i < before; i++) {
            server.takeRequest();
        }
        Cutover cutover = cutover("orders");
        cutover.getSpec().variables = List.of(variable("PASSWORD", "plain", "password"));
        client.resource(cutover).create();
        reconcile("orders");

        Cutover.Status status = status("orders");
        assertThat(status.phase).isEqualTo(Cutover.REFUSED);
        assertThat(status.message).contains("plain")
                .contains(CutoverReconciler.CREDENTIALS + ": \"true\"").doesNotContain("s3cr3t");
        assertThat(engine.plans).isZero();
        List<String> secretReads = new ArrayList<>();
        for (int i = server.getRequestCount() - before; i > 0; i--) {
            var request = server.takeRequest();
            if (request.getPath().contains("/secrets/plain")) {
                secretReads.add(request.getMethod() + " " + request.getHeader("Accept"));
            }
        }
        assertThat(secretReads).isNotEmpty()
                .allSatisfy(read -> assertThat(read).contains("as=PartialObjectMetadata"));
    }

    @Test
    @DisplayName("a management URL off the allowlist is refused before any request reaches it, on"
            + " plan and on rollback; on the allowlist it is asked")
    void allowlist() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer broker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broker.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        broker.start();
        try {
            int port = broker.getAddress().getPort();
            String deployment = Files.readString(Path.of("../scripts/operator-e2e/deployment.yaml"))
                    .replaceAll("(blue|green)\\.acemq-infra-e2e\\.svc:15672", "127.0.0.1:" + port)
                    .replaceAll("(blue|green)\\.acemq-infra-e2e\\.svc:5672", "127.0.0.1:5781");
            List<Cutover.Variable> variables = List.of(literal("BLUE_USERNAME", "u"),
                    literal("BLUE_PASSWORD", "p"), literal("GREEN_USERNAME", "u"),
                    literal("GREEN_PASSWORD", "p"));
            CutoverReconciler strict = new CutoverReconciler(client, new Engine.Rabbit(),
                    Allowlist.parse(null));

            Cutover planned = cutover("planned");
            planned.getSpec().deployment = deployment;
            planned.getSpec().variables = variables;
            client.resource(planned).create();
            strict.handle(fetch("planned"));
            assertThat(status("planned").phase).isEqualTo(Cutover.REFUSED);
            assertThat(status("planned").message).contains("127.0.0.1:" + port)
                    .contains(Allowlist.ENV).contains("nothing was sent");

            Cutover undone = cutover("undone");
            undone.getSpec().deployment = deployment;
            undone.getSpec().variables = variables;
            undone.getSpec().action = Cutover.ROLLBACK;
            client.resource(undone).create();
            setPhase("undone", Cutover.COMPLETED);
            storeJournal("undone", RUNNING_AT_DRAIN);
            strict.handle(fetch("undone"));
            assertThat(status("undone").phase).isEqualTo(Cutover.COMPLETED);
            assertThat(status("undone").message).contains(Allowlist.ENV);
            assertThat(requests.get()).isZero();

            new CutoverReconciler(client, new Engine.Rabbit(), Allowlist.parse("127.0.0.1"))
                    .handle(fetch("planned"));
            assertThat(requests.get()).isPositive();
            assertThat(status("planned").message).doesNotContain(Allowlist.ENV);
        } finally {
            broker.stop(0);
        }
    }

    // ---------------------------------------------------------------- the fake engine

    /** Plans with a fingerprint the test sets, and writes journals the way the CLI does. */
    static final class Recorder implements Engine {

        String fingerprint = "plan-1";
        int plans;
        Map<String, String> env;
        String sources;
        final List<String> files = new ArrayList<>();
        final List<String> applies = new ArrayList<>();
        final List<String> rollbacks = new ArrayList<>();
        boolean refuseRollback;

        @Override
        public Planned plan(Path file, Map<String, String> env, String sources) {
            plans++;
            this.env = env;
            this.sources = sources;
            return new Planned(true, "the plan text\n", fingerprint);
        }

        @Override
        public Ran apply(Path file, Path journal, Map<String, String> env, Journal.Mirror mirror) {
            applies.add(read(file));
            write(journal, mirror, """
                    {"format":1,"outcome":"running","steps":[
                      {"number":1,"index":0,"id":"topology","status":"started"}]}""");
            write(journal, mirror, """
                    {"format":1,"outcome":"completed","steps":[
                      {"number":1,"index":0,"id":"topology","status":"done"},
                      {"number":2,"index":1,"id":"drain-messages","status":"done"}]}""");
            return new Ran(0, "the cutover completed.\n");
        }

        @Override
        public Ran rollback(Path file, Path journal, Map<String, String> env,
                            Journal.Mirror mirror) {
            String before = read(journal);
            files.add(read(file));
            if (refuseRollback) {
                return new Ran(1, "acemq-infra: the cutover's drain is still running.\n");
            }
            rollbacks.add(before);
            write(journal, mirror, before.substring(0, before.lastIndexOf('}'))
                    + ",\"rollback\":{\"outcome\":\"completed\",\"steps\":[]}}");
            return new Ran(0, "rolled back.\n");
        }

        private static void write(Path path, Journal.Mirror mirror, String json) {
            try {
                Files.writeString(path, json);
                mirror.write(json);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }

        private static String read(Path path) {
            try {
                return Files.readString(path);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Planned, approved and run to Completed, with its journal in its ConfigMap. */
    private void complete(String name) {
        create(name);
        reconcile(name);
        approve(name, "plan-1");
        reconcile(name);
        assertThat(status(name).phase).isEqualTo(Cutover.COMPLETED);
    }

    private void undo(String name, String configMap) {
        Cutover cutover = cutover(name);
        cutover.getSpec().action = Cutover.ROLLBACK;
        cutover.getSpec().journalFrom = journalFrom(configMap);
        client.resource(cutover).create();
    }

    private static Cutover.JournalFrom journalFrom(String configMap) {
        Cutover.JournalFrom from = new Cutover.JournalFrom();
        from.configMapRef = new Cutover.ConfigMapRef();
        from.configMapRef.name = configMap;
        return from;
    }

    private ConfigMap configMap(String name) {
        return client.configMaps().inNamespace(NS).withName(name).get();
    }

    private void reconcile(String name) {
        reconciler.handle(fetch(name));
    }

    private Cutover fetch(String name) {
        return client.resources(Cutover.class).inNamespace(NS).withName(name).get();
    }

    private Cutover.Status status(String name) {
        return fetch(name).getStatus();
    }

    private static Cutover cutover(String name) {
        Cutover cutover = new Cutover();
        cutover.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS)
                .withUid(name + "-uid").build());
        cutover.setSpec(new Cutover.Spec());
        cutover.getSpec().deployment = DEPLOYMENT;
        return cutover;
    }

    private void create(String name) {
        client.resource(cutover(name)).create();
    }

    private void approve(String name, String fingerprint) {
        client.resources(Cutover.class).inNamespace(NS).withName(name).edit(c -> {
            c.getSpec().approve = fingerprint;
            return c;
        });
    }

    private void action(String name, String action) {
        client.resources(Cutover.class).inNamespace(NS).withName(name).edit(c -> {
            c.getSpec().action = action;
            return c;
        });
    }

    private void setPhase(String name, String phase) {
        client.resources(Cutover.class).inNamespace(NS).withName(name).editStatus(c -> {
            c.setStatus(new Cutover.Status());
            c.getStatus().phase = phase;
            return c;
        });
    }

    private void storeJournal(String name, String json) {
        client.configMaps().inNamespace(NS).resource(new io.fabric8.kubernetes.api.model
                .ConfigMapBuilder().withNewMetadata().withName(name + "-journal")
                .withNamespace(NS).endMetadata()
                .addToData(CutoverReconciler.JOURNAL_KEY, json).build()).create();
    }

    private String journal(String name) {
        ConfigMap map = client.configMaps().inNamespace(NS).withName(name + "-journal").get();
        return map.getData().get(CutoverReconciler.JOURNAL_KEY);
    }

    private static Cutover.Variable variable(String name, String secret, String key) {
        Cutover.Variable variable = new Cutover.Variable();
        variable.name = name;
        variable.secretKeyRef = new Cutover.SecretKeyRef();
        variable.secretKeyRef.name = secret;
        variable.secretKeyRef.key = key;
        return variable;
    }

    private static Cutover.Variable literal(String name, String value) {
        Cutover.Variable variable = new Cutover.Variable();
        variable.name = name;
        variable.value = value;
        return variable;
    }
}
