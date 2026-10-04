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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

import org.acemq.infra.execute.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The state machine. One rule above all the others: <strong>the journal is the state</strong>, and
 * nothing that was started is ever started again by a reconcile.
 *
 * <p>How that holds:
 *
 * <ul>
 *   <li>The cutover runs inside the reconcile that approved it, and the phase is written as
 *       {@code Applying} before it starts. The SDK never runs two reconciles of one resource at
 *       once, so a reconcile that <em>finds</em> {@code Applying} was not the one running it: the
 *       process that was is gone. It is marked {@code Interrupted} and nothing is resumed.
 *   <li>Every journal write is copied, synchronously, into a ConfigMap owned by the resource, and a
 *       step is recorded as {@code started} before it touches a broker. An apply is refused when
 *       that ConfigMap already exists, whatever the status says, so a lost or restored status
 *       cannot start a second run either.
 *   <li>The status is read from the API server at the top of every reconcile, never from the
 *       informer's cache, which can be a write behind.
 * </ul>
 */
@ControllerConfiguration(finalizerName = CutoverReconciler.FINALIZER)
public class CutoverReconciler implements Reconciler<Cutover>, Cleaner<Cutover> {

    private static final Logger LOG = LoggerFactory.getLogger(CutoverReconciler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The key the journal is kept under, in the ConfigMap. */
    static final String JOURNAL_KEY = "journal.json";

    /** The deployment file, copied beside a retained journal: the rollback needs it verbatim. */
    static final String DEPLOYMENT_KEY = "deployment.yaml";

    /** How each variable was given, beside a retained journal. References, never values. */
    static final String VARIABLES_KEY = "variables";

    /** Holds a deleted Cutover until its journal has been kept or let go. */
    static final String FINALIZER = "infra.acemq.org/journal";

    /** On a journal kept after its Cutover was deleted. */
    static final String RETAINED = "infra.acemq.org/retained";
    static final String CUTOVER = "infra.acemq.org/cutover";
    static final String CUTOVER_UID = "infra.acemq.org/cutover-uid";
    static final String RETAINED_REASON = "infra.acemq.org/retained-reason";

    private static final Set<String> RAN = Set.of(Cutover.COMPLETED, Cutover.FAILED,
            Cutover.INTERRUPTED);

    private static final Set<String> FINISHED = Set.of(Cutover.ROLLED_BACK,
            Cutover.ROLLBACK_FAILED, Cutover.ROLLBACK_INTERRUPTED);

    private final KubernetesClient client;
    private final Engine engine;

    CutoverReconciler(KubernetesClient client, Engine engine) {
        this.client = client;
        this.engine = engine;
    }

    @Override
    public UpdateControl<Cutover> reconcile(Cutover cached, Context<Cutover> context) {
        Cutover fresh = client.resource(cached).get();
        return fresh == null ? UpdateControl.noUpdate() : handle(fresh);
    }

    /**
     * A deleted Cutover's journal is kept when it is still the only record of how to undo a run:
     * the owner reference that would have the garbage collector take it is removed, and it is
     * labelled and annotated with why. Otherwise it goes with the Cutover. Each step is idempotent
     * and the finalizer is released only after the ConfigMap is in its final shape, so an operator
     * stopped half way does the same again on the next pass.
     */
    @Override
    public DeleteControl cleanup(Cutover cutover, Context<Cutover> context) {
        ConfigMap map = configMap(cutover);
        String uid = cutover.getMetadata().getUid();
        // Not ours — never adopted, or already let go of on an earlier pass: nothing to decide.
        if (map == null || !ownedBy(map, uid)) {
            return DeleteControl.defaultDelete();
        }
        String reason = retained(cutover, map);
        if (reason == null) {
            return DeleteControl.defaultDelete();
        }
        String sources = sources(cutover);
        client.configMaps().inNamespace(namespace(cutover)).withName(map.getMetadata().getName())
                .edit(kept -> {
                    kept.getMetadata().getOwnerReferences().removeIf(o -> uid.equals(o.getUid()));
                    kept.getMetadata().getLabels().put(RETAINED, "true");
                    kept.getMetadata().getLabels().put(CUTOVER, name(cutover));
                    kept.getMetadata().getLabels().put(CUTOVER_UID, uid);
                    kept.getMetadata().getAnnotations().put(RETAINED_REASON, reason);
                    kept.getData().putIfAbsent(DEPLOYMENT_KEY, cutover.getSpec().deployment);
                    kept.getData().putIfAbsent(VARIABLES_KEY, sources);
                    return kept;
                });
        LOG.info("{}/{}: deleted; journal {} kept: {}", namespace(cutover), name(cutover),
                map.getMetadata().getName(), reason);
        return DeleteControl.defaultDelete();
    }

    /** Why a deleted Cutover's journal must outlive it, or null when it need not. */
    private static String retained(Cutover cutover, ConfigMap map) {
        String who = "Cutover " + name(cutover) + " (uid " + cutover.getMetadata().getUid() + ")";
        JsonNode journal;
        try {
            journal = JSON.readTree(map.getData() == null ? null : map.getData().get(JOURNAL_KEY));
        } catch (IOException | IllegalArgumentException unreadable) {
            journal = null;
        }
        if (journal == null || !journal.isObject()) {
            return who + " was deleted and its journal cannot be read; kept rather than guessed"
                    + " at.";
        }
        JsonNode rollback = journal.path("rollback");
        if (rollback.isObject()) {
            String outcome = rollback.path("outcome").asText("running");
            return "completed".equals(outcome) ? null : who + " was deleted after a rollback"
                    + " that ended " + outcome + ". Neither the operator nor the CLI starts a"
                    + " second one; kept to finish by hand.";
        }
        if (journal.path("steps").isEmpty()) {
            return null; // nothing started, so nothing to undo
        }
        Cutover.Status s = new Cutover.Status();
        summarise(s, journal);
        return who + " was deleted with its journal " + s.journalOutcome + ", last step "
                + s.lastStep + ", and not rolled back. Kept so it can be: a Cutover with"
                + " spec.action: rollback and spec.journalFrom naming this ConfigMap, or"
                + " acemq-infra rollback --journal with the journal.json and deployment.yaml"
                + " here.";
    }

    private static boolean ownedBy(ConfigMap map, String uid) {
        return map.getMetadata().getOwnerReferences().stream()
                .anyMatch(owner -> uid.equals(owner.getUid()));
    }

    /** One pass of the state machine
    /** One pass of the state machine over the resource as the API server has it now. */
    UpdateControl<Cutover> handle(Cutover cutover) {
        Cutover.Status status = cutover.getStatus() == null ? new Cutover.Status()
                : cutover.getStatus();
        String phase = status.phase == null ? "" : status.phase;
        Cutover.Spec spec = cutover.getSpec();

        // Found mid-run: whoever was running it is not running it now.
        if (phase.equals(Cutover.APPLYING)) {
            interrupted(cutover, "the operator stopped while this cutover was running");
            return UpdateControl.noUpdate();
        }
        if (phase.equals(Cutover.ROLLING_BACK)) {
            JsonNode journal = journal(cutover);
            if (journal != null && journal.path("rollback").isObject()) {
                write(cutover, s -> {
                    summarise(s, journal);
                    s.phase = Cutover.ROLLBACK_INTERRUPTED;
                    s.message = "the operator stopped while the rollback was running. The journal"
                            + " is marked rolled back and refuses a second rollback; finish it by"
                            + " hand from " + s.journal + ".";
                });
                return UpdateControl.noUpdate();
            }
            // Stopped before the rollback's first write: nothing to have done twice.
            interrupted(cutover, "the operator stopped before the rollback wrote anything");
            return Cutover.ROLLBACK.equals(spec.action)
                    ? rollback(client.resource(cutover).get(), Cutover.INTERRUPTED)
                    : UpdateControl.noUpdate();
        }

        if (spec.journalFrom != null) {
            if (!Cutover.ROLLBACK.equals(spec.action)) {
                write(cutover, s -> {
                    s.phase = Cutover.REFUSED;
                    s.message = "spec.journalFrom is for undoing another Cutover's run, and only"
                            + " with spec.action: rollback. This resource plans and applies"
                            + " nothing.";
                });
                return UpdateControl.noUpdate();
            }
            if (!RAN.contains(phase) && !FINISHED.contains(phase)) {
                String refused = adopt(cutover);
                if (refused != null) {
                    write(cutover, s -> {
                        s.phase = Cutover.REFUSED;
                        s.message = refused + " Nothing was written.";
                    });
                    return UpdateControl.noUpdate();
                }
                // Now in a phase that ran, with the journal as its own: the rollback below.
                return handle(client.resource(cutover).get());
            }
        }
        if (Cutover.ROLLBACK.equals(spec.action)) {
            if (RAN.contains(phase)) {
                return rollback(cutover, phase);
            }
            if (!FINISHED.contains(phase)) {
                write(cutover, s -> s.message = "spec.action is rollback, and no cutover has run"
                        + " from this resource: there is nothing to undo.");
            }
            return UpdateControl.noUpdate();
        }
        if (RAN.contains(phase) || FINISHED.contains(phase)) {
            return UpdateControl.noUpdate();
        }

        // Pending, Planned or Refused: plan, and apply if the approval names this plan.
        if (journal(cutover) != null) {
            interrupted(cutover, "a journal already exists for this resource and its status does"
                    + " not say why. A second run is never started over a first one's journal");
            return UpdateControl.noUpdate();
        }
        Map<String, String> env;
        Engine.Planned plan;
        Path work = null;
        try {
            env = variables(cutover);
            work = Files.createTempDirectory("cutover-");
            Path file = work.resolve("deployment.yaml");
            Files.writeString(file, spec.deployment, StandardCharsets.UTF_8);
            plan = engine.plan(file, env, sources(cutover));

            if (!plan.ok()) {
                Engine.Planned refused = plan;
                write(cutover, s -> planned(s, cutover, refused, Cutover.REFUSED,
                        "the plan is refused, or could not be made. Nothing was written."));
                return UpdateControl.<Cutover>noUpdate().rescheduleAfter(Duration.ofMinutes(1));
            }
            if (Boolean.TRUE.equals(spec.dryRun)) {
                Engine.Planned dry = plan;
                write(cutover, s -> planned(s, cutover, dry, Cutover.PLANNED,
                        "dry run: planned, and never applied while spec.dryRun is true."));
                return UpdateControl.noUpdate();
            }
            if (spec.approve == null || spec.approve.isBlank()) {
                Engine.Planned waiting = plan;
                write(cutover, s -> planned(s, cutover, waiting, Cutover.PLANNED,
                        "read the plan, then set spec.approve: " + waiting.fingerprint()
                                + " to run it."));
                return UpdateControl.noUpdate();
            }
            if (!spec.approve.equals(plan.fingerprint())) {
                Engine.Planned changed = plan;
                write(cutover, s -> planned(s, cutover, changed, Cutover.PLANNED,
                        "spec.approve names plan " + spec.approve + ", and the plan is now "
                                + changed.fingerprint() + ". An approval is for one plan; this"
                                + " one has not been approved. Nothing was written."));
                return UpdateControl.noUpdate();
            }
            return apply(cutover, plan, file, env, work);
        } catch (IOException | RuntimeException failed) {
            String why = failed.getMessage();
            LOG.warn("{}/{}: cannot plan: {}", namespace(cutover), name(cutover), why);
            write(cutover, s -> {
                s.phase = Cutover.REFUSED;
                s.message = "cannot plan: " + why;
                s.observedGeneration = cutover.getMetadata().getGeneration();
            });
            return UpdateControl.<Cutover>noUpdate().rescheduleAfter(Duration.ofMinutes(1));
        } finally {
            delete(work);
        }
    }

    // ---------------------------------------------------------------- apply

    private UpdateControl<Cutover> apply(Cutover cutover, Engine.Planned plan, Path file,
                                         Map<String, String> env, Path work) {
        String configMap = journalName(cutover);
        // Written before anything runs, and the run does not start if it cannot be: this is the
        // record that makes a restart an Interrupted rather than a second start.
        write(cutover, s -> {
            planned(s, cutover, plan, Cutover.APPLYING, "the cutover is running. Its journal is"
                    + " ConfigMap " + configMap + ".");
            s.journal = configMap;
        });
        LOG.info("{}/{}: approved plan {}, applying", namespace(cutover), name(cutover),
                plan.fingerprint());

        Path journalFile = work.resolve("journal.json");
        Engine.Ran ran;
        try {
            ran = engine.apply(file, journalFile, env, mirror(cutover, true));
        } catch (RuntimeException failed) {
            ran = new Engine.Ran(1, "the run failed: " + failed);
        }
        LOG.info("{}/{}: apply exited {}\n{}", namespace(cutover), name(cutover), ran.code(),
                ran.output());

        JsonNode journal = readJournal(journalFile);
        Engine.Ran result = ran;
        write(cutover, s -> {
            if (journal == null) {
                s.phase = Cutover.FAILED;
                s.message = "the cutover did not start, and nothing was written:\n"
                        + tail(result.output());
                return;
            }
            summarise(s, journal);
            boolean completed = "completed".equals(s.journalOutcome);
            s.phase = completed ? Cutover.COMPLETED : Cutover.FAILED;
            s.message = (completed ? "the cutover completed." : "the cutover ended "
                    + s.journalOutcome + ".") + " Set spec.action: rollback to undo it.\n"
                    + tail(result.output());
        });
        return UpdateControl.noUpdate();
    }

    // ---------------------------------------------------------------- rollback

    private UpdateControl<Cutover> rollback(Cutover cutover, String before) {
        ConfigMap stored = configMap(cutover);
        if (stored == null || stored.getData() == null
                || !stored.getData().containsKey(JOURNAL_KEY)) {
            write(cutover, s -> s.message = "spec.action is rollback and there is no journal:"
                    + " the cutover never wrote one, so it wrote nothing to a broker either.");
            return UpdateControl.noUpdate();
        }
        Path work = null;
        try {
            Map<String, String> env = variables(cutover);
            work = Files.createTempDirectory("cutover-");
            Path file = work.resolve("deployment.yaml");
            Path journalFile = work.resolve("journal.json");
            Files.writeString(file, cutover.getSpec().deployment, StandardCharsets.UTF_8);
            Files.writeString(journalFile, stored.getData().get(JOURNAL_KEY),
                    StandardCharsets.UTF_8);

            write(cutover, s -> {
                s.phase = Cutover.ROLLING_BACK;
                s.message = "the rollback is running.";
            });
            Engine.Ran ran = engine.rollback(file, journalFile, env, mirror(cutover, false));
            LOG.info("{}/{}: rollback exited {}\n{}", namespace(cutover), name(cutover),
                    ran.code(), ran.output());

            JsonNode journal = readJournal(journalFile);
            JsonNode marked = journal == null ? null : journal.path("rollback");
            if (marked == null || !marked.isObject()) {
                if (ran.code() == 0) {
                    write(cutover, s -> {
                        s.phase = Cutover.ROLLED_BACK;
                        s.message = tail(ran.output());
                    });
                    return UpdateControl.noUpdate();
                }
                // Refused before its first write. Nothing happened, so it is safe to ask again;
                // the usual reason is a drain that has not finished yet.
                write(cutover, s -> {
                    s.phase = before;
                    s.message = "the rollback was refused and nothing was written. It is tried"
                            + " again while spec.action is rollback.\n" + tail(ran.output());
                });
                return UpdateControl.<Cutover>noUpdate().rescheduleAfter(Duration.ofSeconds(30));
            }
            write(cutover, s -> {
                summarise(s, journal);
                boolean done = "completed".equals(s.rollbackOutcome);
                s.phase = done ? Cutover.ROLLED_BACK : Cutover.ROLLBACK_FAILED;
                s.message = tail(ran.output());
            });
            return UpdateControl.noUpdate();
        } catch (IOException | RuntimeException failed) {
            String why = failed.getMessage();
            write(cutover, s -> {
                s.phase = before;
                s.message = "the rollback could not be started: " + why;
            });
            return UpdateControl.<Cutover>noUpdate().rescheduleAfter(Duration.ofSeconds(30));
        } finally {
            delete(work);
        }
    }

    // ---------------------------------------------------------------- a retained journal

    /**
     * Takes the retained journal {@code spec.journalFrom} names, by becoming its owner, and writes
     * the run it records into status, so the rollback below proceeds as for this resource's own.
     * The owner reference is the claim: a journal already owned by another Cutover is refused,
     * and the update carries the resourceVersion read, so of two Cutovers racing for one journal
     * only one takes it.
     *
     * @return why it cannot be taken, or null when it was
     */
    private String adopt(Cutover cutover) {
        String name = journalName(cutover);
        ConfigMap map = configMap(cutover);
        if (map == null) {
            return "spec.journalFrom names ConfigMap " + name + ", and there is no such ConfigMap"
                    + " in namespace " + namespace(cutover) + ".";
        }
        String uid = cutover.getMetadata().getUid();
        if (!ownedBy(map, uid)) {
            if (!"true".equals(map.getMetadata().getLabels().get(RETAINED))) {
                return "ConfigMap " + name + " is not a retained journal: it has no label "
                        + RETAINED + "=true. Only a journal the operator kept when its Cutover was"
                        + " deleted is adopted.";
            }
            if (!map.getMetadata().getOwnerReferences().isEmpty()) {
                return "the journal in ConfigMap " + name + " has been taken by "
                        + map.getMetadata().getOwnerReferences().get(0).getKind() + " "
                        + map.getMetadata().getOwnerReferences().get(0).getName()
                        + ". One journal is rolled back by one Cutover.";
            }
            if (map.getData() == null || !map.getData().containsKey(JOURNAL_KEY)) {
                return "ConfigMap " + name + " has no " + JOURNAL_KEY + ".";
            }
            map.getMetadata().getOwnerReferences().add(ownerReference(cutover));
            try {
                client.configMaps().inNamespace(namespace(cutover)).resource(map).update();
            } catch (RuntimeException lost) {
                return "the journal in ConfigMap " + name + " could not be taken: "
                        + lost.getMessage();
            }
            LOG.info("{}/{}: took the retained journal {}", namespace(cutover), name(cutover),
                    name);
        }
        JsonNode journal = journal(cutover);
        write(cutover, s -> {
            summarise(s, journal);
            s.journal = name;
            s.observedGeneration = cutover.getMetadata().getGeneration();
            s.phase = switch (String.valueOf(s.journalOutcome)) {
                case "completed" -> Cutover.COMPLETED;
                case "running" -> Cutover.INTERRUPTED;
                default -> Cutover.FAILED;
            };
            s.message = "took the retained journal " + name + " (journal " + s.journalOutcome
                    + ", last step " + s.lastStep + ") to roll it back.";
        });
        return null;
    }

    // ---------------------------------------------------------------- the journal, in the cluster

    /**
     * Every journal write, copied into the resource's ConfigMap before the call that made it
     * returns. The first write of an apply creates it, and fails — so the run does not start — if
     * one is already there.
     */
    private Journal.Mirror mirror(Cutover cutover, boolean creating) {
        boolean[] first = {creating};
        return json -> {
            try {
                if (first[0]) {
                    client.configMaps().inNamespace(namespace(cutover)).resource(
                            new ConfigMapBuilder()
                                    .withNewMetadata()
                                    .withName(journalName(cutover))
                                    .withNamespace(namespace(cutover))
                                    .addToLabels("app.kubernetes.io/managed-by",
                                            "acemq-infra-operator")
                                    .withOwnerReferences(ownerReference(cutover))
                                    .endMetadata()
                                    .addToData(JOURNAL_KEY, json)
                                    .build()).create();
                    first[0] = false;
                } else {
                    client.configMaps().inNamespace(namespace(cutover))
                            .withName(journalName(cutover))
                            .edit(map -> {
                                map.getData().put(JOURNAL_KEY, json);
                                return map;
                            });
                }
            } catch (RuntimeException failed) {
                throw new IOException(failed.getMessage(), failed);
            }
            // Progress, best effort: the ConfigMap is the record and the status a summary of it.
            try {
                JsonNode journal = JSON.readTree(json);
                write(cutover, s -> summarise(s, journal));
            } catch (IOException | RuntimeException ignored) {
                LOG.debug("status progress not written", ignored);
            }
        };
    }

    private void interrupted(Cutover cutover, String what) {
        JsonNode journal = journal(cutover);
        LOG.warn("{}/{}: {}; marking Interrupted", namespace(cutover), name(cutover), what);
        write(cutover, s -> {
            s.phase = Cutover.INTERRUPTED;
            if (journal == null) {
                s.message = what + ". There is no journal, so the cutover had not begun writing."
                        + " Nothing is resumed; create a new Cutover to run it.";
                return;
            }
            summarise(s, journal);
            s.journal = journalName(cutover);
            s.message = what + ". The journal says " + s.journalOutcome + ", and the last step"
                    + " it records is " + s.lastStep + ". Nothing is resumed: a step that was"
                    + " started is never run again by the operator. Set spec.action: rollback to"
                    + " undo what happened — a step left at started counts as having happened —"
                    + " or finish it by hand.";
        });
    }

    /** The journal as the ConfigMap holds it, or null when there is none. */
    private JsonNode journal(Cutover cutover) {
        ConfigMap map = configMap(cutover);
        if (map == null || map.getData() == null || !map.getData().containsKey(JOURNAL_KEY)) {
            return map == null ? null : JSON.createObjectNode();
        }
        try {
            return JSON.readTree(map.getData().get(JOURNAL_KEY));
        } catch (IOException unreadable) {
            return JSON.createObjectNode();
        }
    }

    private ConfigMap configMap(Cutover cutover) {
        return client.configMaps().inNamespace(namespace(cutover))
                .withName(journalName(cutover)).get();
    }

    private static JsonNode readJournal(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The journal, boiled down into status. */
    static void summarise(Cutover.Status s, JsonNode journal) {
        s.journalOutcome = journal.path("outcome").asText(null);
        List<String> steps = new ArrayList<>();
        String last = null;
        for (JsonNode step : journal.path("steps")) {
            String line = step.path("number").asText() + " " + step.path("id").asText() + " "
                    + step.path("status").asText();
            steps.add(line);
            last = line;
        }
        s.steps = steps;
        s.lastStep = last;
        JsonNode rollback = journal.path("rollback");
        if (rollback.isObject()) {
            s.rollbackOutcome = rollback.path("outcome").asText(null);
            for (JsonNode step : rollback.path("steps")) {
                s.steps.add("rollback " + step.path("number").asText() + " "
                        + step.path("id").asText() + " " + step.path("status").asText());
            }
        }
    }

    private static void planned(Cutover.Status s, Cutover cutover, Engine.Planned plan,
                                String phase, String message) {
        s.phase = phase;
        s.message = message;
        s.plan = plan.text();
        s.planFingerprint = plan.fingerprint();
        s.observedGeneration = cutover.getMetadata().getGeneration();
    }

    // ---------------------------------------------------------------- variables and secrets

    /**
     * The {@code ${VAR}} values, with every Secret read from the resource's own namespace and
     * nowhere else. A missing Secret or key is named; its value never is.
     */
    Map<String, String> variables(Cutover cutover) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Cutover.Variable variable : variablesOf(cutover)) {
            if (variable.secretKeyRef != null) {
                Secret secret = client.secrets().inNamespace(namespace(cutover))
                        .withName(variable.secretKeyRef.name).get();
                String encoded = secret == null || secret.getData() == null ? null
                        : secret.getData().get(variable.secretKeyRef.key);
                if (encoded == null) {
                    throw new IllegalArgumentException("variable " + variable.name + " reads key '"
                            + variable.secretKeyRef.key + "' of Secret "
                            + variable.secretKeyRef.name + " in namespace " + namespace(cutover)
                            + ", and there is no such " + (secret == null ? "Secret" : "key")
                            + ".");
                }
                env.put(variable.name, new String(Base64.getDecoder().decode(encoded),
                        StandardCharsets.UTF_8));
            } else {
                env.put(variable.name, variable.value == null ? "" : variable.value);
            }
        }
        return env;
    }

    /** How each variable was given, for the fingerprint: the reference, never the secret. */
    private static String sources(Cutover cutover) {
        StringBuilder sources = new StringBuilder();
        for (Cutover.Variable variable : variablesOf(cutover)) {
            sources.append(variable.name).append('=').append(variable.secretKeyRef != null
                    ? "secret:" + variable.secretKeyRef.name + "/" + variable.secretKeyRef.key
                    : "value:" + variable.value).append('\n');
        }
        return sources.toString();
    }

    private static List<Cutover.Variable> variablesOf(Cutover cutover) {
        return cutover.getSpec().variables == null ? List.of() : cutover.getSpec().variables;
    }

    // ---------------------------------------------------------------- plumbing

    /** Status, read fresh and written through the status subresource. */
    private void write(Cutover cutover, Consumer<Cutover.Status> change) {
        client.resources(Cutover.class).inNamespace(namespace(cutover)).withName(name(cutover))
                .editStatus(current -> {
                    if (current.getStatus() == null) {
                        current.setStatus(new Cutover.Status());
                    }
                    change.accept(current.getStatus());
                    return current;
                });
    }

    /** The journal's ConfigMap: this resource's own, or the retained one it adopts. */
    static String journalName(Cutover cutover) {
        Cutover.JournalFrom from = cutover.getSpec() == null ? null : cutover.getSpec().journalFrom;
        return from != null && from.configMapRef != null && from.configMapRef.name != null
                ? from.configMapRef.name : name(cutover) + "-journal";
    }

    private static OwnerReference ownerReference(Cutover cutover) {
        return new OwnerReferenceBuilder()
                .withApiVersion(cutover.getApiVersion())
                .withKind(cutover.getKind())
                .withName(name(cutover))
                .withUid(cutover.getMetadata().getUid())
                .build();
    }

    private static String name(Cutover cutover) {
        return cutover.getMetadata().getName();
    }

    private static String namespace(Cutover cutover) {
        return cutover.getMetadata().getNamespace();
    }

    /** The last lines of the CLI's output, which is where it says how things ended. */
    static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A temporary directory; the pod's disk goes with the pod.
        }
    }
}
