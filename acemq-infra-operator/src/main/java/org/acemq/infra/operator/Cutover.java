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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * One cutover, as a custom resource: the deployment file the CLI reads, the variables it is read
 * with, and a human's approval of the plan by name.
 *
 * <p>One resource is one run. It is planned, approved, applied once, and then at most rolled back
 * once; a second cutover is a second resource. Nothing here is desired state — docs/shape.md is
 * the reason — and the status is a record of what happened, not a target to drive towards.
 */
@Group("infra.acemq.org")
@Version("v1alpha1")
@ShortNames("cut")
public class Cutover extends CustomResource<Cutover.Spec, Cutover.Status> implements Namespaced {

    /** Before anything was asked of the clusters. */
    public static final String PENDING = "Pending";
    /** The plan is in status and waits for {@code spec.approve} to name it. */
    public static final String PLANNED = "Planned";
    /** The plan could not be made, or the planner refused it. Re-planned on a change. */
    public static final String REFUSED = "Refused";
    /** The cutover is running, in this operator, now. */
    public static final String APPLYING = "Applying";
    /** It ran to the end. */
    public static final String COMPLETED = "Completed";
    /** It ran and did not reach the end: aborted, stopped, or refused in preflight. */
    public static final String FAILED = "Failed";
    /** The operator stopped while it ran. Never resumed: a human decides. */
    public static final String INTERRUPTED = "Interrupted";
    /** The rollback is running. */
    public static final String ROLLING_BACK = "RollingBack";
    /** Undone. */
    public static final String ROLLED_BACK = "RolledBack";
    /** A rollback ran and did not reach the end. */
    public static final String ROLLBACK_FAILED = "RollbackFailed";
    /** The operator stopped while the rollback ran. The journal refuses a second one. */
    public static final String ROLLBACK_INTERRUPTED = "RollbackInterrupted";

    /** The one value {@code spec.action} takes. */
    public static final String ROLLBACK = "rollback";

    /** What a person writes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Spec {

        @Required
        @JsonPropertyDescription("The deployment file, verbatim: the same document acemq-infra"
                + " validate, plan and apply read. ${VAR} references are resolved from"
                + " spec.variables.")
        public String deployment;

        @JsonPropertyDescription("Values for the ${VAR} references in the deployment file, each a"
                + " literal value or a key of a Secret in this namespace. Credentials belong in"
                + " Secrets; nothing here is copied into status or the journal.")
        public List<Variable> variables = new ArrayList<>();

        @JsonPropertyDescription("The fingerprint of the plan being approved, copied from"
                + " status.planFingerprint. The cutover starts only when this names the plan as it"
                + " is when probed again; an approval of an older plan is refused.")
        public String approve;

        @JsonPropertyDescription("Plan only. The plan is written into status and never applied,"
                + " whatever spec.approve says.")
        public Boolean dryRun;

        @JsonPropertyDescription("'rollback' undoes what the cutover's journal records, through"
                + " the same derivation and refusals as acemq-infra rollback.")
        @Pattern("^(rollback)?$")
        public String action;

        @JsonPropertyDescription("Roll back from a journal another Cutover left behind: a"
                + " ConfigMap in this namespace the operator kept, labelled"
                + " infra.acemq.org/retained=true, when that Cutover was deleted unrolled-back."
                + " Only with action: rollback; this resource then never plans or applies.")
        public JournalFrom journalFrom;
    }

    /** Where an adopted journal is. */
    public static class JournalFrom {

        @Required
        @JsonPropertyDescription("The retained journal's ConfigMap, in this namespace.")
        public ConfigMapRef configMapRef;
    }

    /** A ConfigMap in the resource's own namespace. */
    public static class ConfigMapRef {

        @Required
        public String name;
    }

    /** One {@code ${VAR}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Variable {

        @Required
        @JsonPropertyDescription("The variable's name, as the deployment file writes it inside"
                + " ${...}.")
        public String name;

        @JsonPropertyDescription("A literal value. For anything secret use secretKeyRef.")
        public String value;

        @JsonPropertyDescription("A key of a Secret in this resource's namespace — for instance"
                + " the <cluster>-default-user Secret the RabbitMQ Cluster Operator writes.")
        public SecretKeyRef secretKeyRef;
    }

    /** A key of a Secret in the resource's own namespace. */
    public static class SecretKeyRef {

        @Required
        public String name;

        @Required
        public String key;
    }

    /** What happened, written only by the operator. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Status {

        @PrinterColumn(name = "Phase")
        @JsonPropertyDescription("Pending, Planned, Refused, Applying, Completed, Failed,"
                + " Interrupted, RollingBack, RolledBack, RollbackFailed or RollbackInterrupted.")
        public String phase;

        @JsonPropertyDescription("What the operator wants a human to know now.")
        public String message;

        @PrinterColumn(name = "Plan")
        @JsonPropertyDescription("The fingerprint of the plan below. Copy it into spec.approve to"
                + " run it.")
        public String planFingerprint;

        @JsonPropertyDescription("The plan, as acemq-infra plan prints it.")
        public String plan;

        @JsonPropertyDescription("The generation the plan was made from.")
        public Long observedGeneration;

        @JsonPropertyDescription("The ConfigMap holding the journal, under the key journal.json."
                + " It is the state: the operator reads it, never its own memory.")
        public String journal;

        @PrinterColumn(name = "Journal")
        @JsonPropertyDescription("The journal's outcome: running, completed, aborted, stopped or"
                + " refused.")
        public String journalOutcome;

        @PrinterColumn(name = "Step")
        @JsonPropertyDescription("The last step the journal records as started, and how it"
                + " stands.")
        public String lastStep;

        @JsonPropertyDescription("Every step in the journal: number, id and status.")
        public List<String> steps;

        @JsonPropertyDescription("The rollback's outcome, once one has started.")
        public String rollbackOutcome;
    }
}
