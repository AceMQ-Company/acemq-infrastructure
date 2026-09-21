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
package org.acemq.infra.execute;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.acemq.infra.config.Deployment;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.Step;
import org.acemq.infra.plan.DefaultSteps;
import org.acemq.infra.provider.ProbedCluster;

/**
 * One execution, described completely before any of it happens.
 *
 * <p>The shape of this class is the answer to a requirement rather than a matter of taste: nothing
 * destructive may be reachable by accident from this module's API. A record with a
 * {@code boolean dryRun} field would fail that on the first call site that forgot it, and an enum
 * with a default would fail it on the first caller who did not set one. So there is no default and
 * no boolean. There are two terminal methods on the builder with two different names:
 *
 * <pre>{@code
 * Run rehearsal = Run.of(file).from(blue).to(green).rehearsal();
 * Run cutover   = Run.of(file).from(blue).to(green).cutover();
 * }</pre>
 *
 * <p>A caller cannot arrive at a writing run by leaving something out, because leaving something
 * out does not compile. The word on the screen at the call site is the word for what will happen.
 *
 * <p>The second half of the same guarantee is in {@link Executor}: a rehearsal wraps every
 * {@link Broker} in {@link Rehearsal}, which throws on every writing verb. So the mode is not a
 * flag that each step has to remember to honour — a step that forgot would fail loudly in a
 * rehearsal rather than quietly write to a production cluster.
 */
public final class Run {

    /** What this run is allowed to do. */
    public enum Mode {

        /**
         * Reads everything, writes nothing, and reports what each step would do at this moment.
         * This is what {@code --dry-run} is.
         */
        REHEARSE,

        /** Carries the steps out. */
        EXECUTE
    }

    /**
     * One of the two clusters, with everything needed to reach it and everything already known
     * about it.
     *
     * @param name the name the deployment file gave it
     * @param probed what {@code probe()} found, which is what the capability assertions are made
     *     against. Taken before the run, because a run that re-probed between steps would be a run
     *     whose refusals moved underneath it
     * @param broker the verbs, against this cluster
     * @param amqpUri this cluster's AMQP URI <em>as the other broker will dial it</em>. A shovel
     *     and a federation link both run inside a broker and reach across the network from there,
     *     so this is not the address the operator's laptop uses
     */
    public record Side(String name, ProbedCluster probed, Broker broker, String amqpUri) {

        public Side {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(probed, "probed");
            Objects.requireNonNull(broker, "broker");
            Objects.requireNonNull(amqpUri, "amqpUri");
        }
    }

    private final DeploymentFile file;
    private final List<Step> steps;
    private final Side from;
    private final Side to;
    private final Mode mode;
    private final Console console;
    private final Timing timing;
    private final Duration settle;
    private final Map<String, Side> sides = new LinkedHashMap<>();

    private Run(Builder builder, Mode mode) {
        this.file = builder.file;
        this.from = Objects.requireNonNull(builder.from, "the run has no 'from' cluster");
        this.to = Objects.requireNonNull(builder.to, "the run has no 'to' cluster");
        this.mode = mode;
        this.console = builder.console;
        this.timing = builder.timing;
        this.settle = builder.settle;
        this.steps = builder.steps != null ? List.copyOf(builder.steps) : defaultSteps(builder.file);
        sides.put(from.name(), from);
        sides.put(to.name(), to);
    }

    private static List<Step> defaultSteps(DeploymentFile file) {
        Deployment deployment = file.deployment().orElseThrow(() -> new IllegalArgumentException(
                "a file with no deployment: block cannot be executed. Validate it first."));
        // The same list the plan printed, from the same place, rather than a second copy the
        // executor understands. docs/configuration.md's promise is that the printed list is a list
        // a file could have written, and it stops being true the moment the executor has its own.
        return deployment.steps().orElseGet(() -> DefaultSteps.of(deployment));
    }

    /**
     * Starts describing a run.
     *
     * @param file the deployment file, already validated
     * @return a builder, which has no way to produce a run that writes without the word being
     *     typed
     */
    public static Builder of(DeploymentFile file) {
        return new Builder(Objects.requireNonNull(file, "file"));
    }

    /** The deployment file, for the announcement envelope, the endpoint block and the backup. */
    public DeploymentFile file() {
        return file;
    }

    /** The steps, as written or as filled in. */
    public List<Step> steps() {
        return steps;
    }

    /** The cluster being moved away from. */
    public Side from() {
        return from;
    }

    /** The cluster being moved to. */
    public Side to() {
        return to;
    }

    /** Whether this run may write. */
    public Mode mode() {
        return mode;
    }

    /** The human, if there is one. */
    public Console console() {
        return console;
    }

    /** The clock and the wait. */
    public Timing timing() {
        return timing;
    }

    /** How long a guard's condition has to hold before it is believed — {@link Guards#SETTLE}. */
    public Duration settle() {
        return settle;
    }

    /**
     * A cluster by the name the file gave it.
     *
     * @param name the file's name for a cluster
     * @return that side, or empty when the file named one this run does not have
     */
    public Optional<Side> side(String name) {
        return Optional.ofNullable(sides.get(name));
    }

    /** Assembles a {@link Run}. */
    public static final class Builder {

        private final DeploymentFile file;
        private Side from;
        private Side to;
        private List<Step> steps;
        private Console console = Console.unattended(line -> { });
        private Timing timing = Timing.real();
        private Duration settle = Guards.SETTLE;

        private Builder(DeploymentFile file) {
            this.file = file;
        }

        /**
         * The cluster being moved away from.
         *
         * @param side the source
         * @return this builder
         */
        public Builder from(Side side) {
            this.from = side;
            return this;
        }

        /**
         * The cluster being moved to.
         *
         * @param side the target
         * @return this builder
         */
        public Builder to(Side side) {
            this.to = side;
            return this;
        }

        /**
         * The steps, when they are not the file's own — which is how a rollback is run.
         *
         * @param list the steps to carry out, in order
         * @return this builder
         */
        public Builder steps(List<Step> list) {
            this.steps = list;
            return this;
        }

        /**
         * Who is watching.
         *
         * @param value the console; the default has nobody behind it
         * @return this builder
         */
        public Builder console(Console value) {
            this.console = value;
            return this;
        }

        /**
         * The clock and the wait.
         *
         * @param value the timing; the default is the wall clock
         * @return this builder
         */
        public Builder timing(Timing value) {
            this.timing = value;
            return this;
        }

        /**
         * How long a guard's condition must hold before it is believed.
         *
         * <p>Worth raising on a cluster whose {@code collect_statistics_interval} has been set
         * above RabbitMQ's default five seconds, and not worth lowering — {@link Guards} has the
         * argument, and scripts/blue-green-lab.sh has the evidence.
         *
         * @param value the window
         * @return this builder
         */
        public Builder allowingForStatistics(Duration value) {
            this.settle = value;
            return this;
        }

        /**
         * A run that reads everything and writes nothing.
         *
         * @return the rehearsal
         */
        public Run rehearsal() {
            return new Run(this, Mode.REHEARSE);
        }

        /**
         * A run that carries the steps out against the two real clusters.
         *
         * <p>Named rather than flagged, so that the call site says what it does.
         *
         * @return the cutover
         */
        public Run cutover() {
            return new Run(this, Mode.EXECUTE);
        }
    }
}
