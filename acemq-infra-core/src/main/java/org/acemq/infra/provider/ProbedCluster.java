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
package org.acemq.infra.provider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one cluster turned out to be, the moment it was asked.
 *
 * <p>This record is the reason the planner can be tested. A probe result is a <em>value</em>: a
 * test writes down a cluster running 3.12 with the shovel plugin missing and gets the plan that
 * estate would produce, in milliseconds, with no container anywhere. If {@code probe()} returned a
 * live client instead, every planner test would need a broker and the planner would be free to go
 * back and ask for more — which is the same thing as being free to write.
 *
 * <p>Every capability carries a verdict rather than being in or out of a set, because the useful
 * half of "DRAIN_BY_SHOVEL is missing" is the sentence after it. docs/broker-agnostic.md sets the
 * bar: the value of the capability model is turning "the cutover failed halfway" into "blue has
 * rabbitmq_shovel disabled, so step drain-messages cannot run; enable the plugin or choose a
 * different drain". A set of enums cannot say the second half.
 *
 * @param name the name the deployment file gave this cluster
 * @param product what is running, for the plan's first line: {@code RabbitMQ}
 * @param version the version it reported
 * @param facilities the short plugin-level facts the probe summary prints, in the order a reader
 *     wants them rather than alphabetically
 * @param capabilities every capability, with why it is present or absent
 * @param inventory what was counted
 * @param notes things the probe could not determine and refuses to guess at
 */
public record ProbedCluster(String name, String product, String version, List<Facility> facilities,
                            Map<Capability, Verdict> capabilities, Inventory inventory,
                            List<String> notes) {

    public ProbedCluster {
        facilities = List.copyOf(facilities);
        capabilities = Map.copyOf(capabilities);
        notes = List.copyOf(notes);
    }

    /** Whether the cluster can do this. A capability nobody asked about is absent, not unknown. */
    public boolean can(Capability capability) {
        Verdict verdict = capabilities.get(capability);
        return verdict != null && verdict.present();
    }

    /** Why it cannot, in the words the plan should print. */
    public Optional<String> whyNot(Capability capability) {
        Verdict verdict = capabilities.get(capability);
        if (verdict == null) {
            return Optional.of("the probe did not establish it");
        }
        return verdict.present() ? Optional.empty() : Optional.of(verdict.because());
    }

    /** The capabilities that are present, for the callers that only need the set. */
    public Set<Capability> present() {
        Set<Capability> found = new LinkedHashSet<>();
        for (Capability capability : Capability.values()) {
            if (can(capability)) {
                found.add(capability);
            }
        }
        return found;
    }

    /** {@code RabbitMQ 3.13.7}, which the probe summary pads into a column of its own. */
    public String release() {
        return product + " " + version;
    }

    /**
     * {@code shovel✓ federation✓ streams✗}, the rest of that line.
     *
     * <p>Marks rather than words because two clusters' worth of these are read as a column, and a
     * column is read by shape. Kept separate from {@link #release()} so that the caller holding
     * both clusters can line the two up; a cluster on its own does not know how wide the version
     * column has to be.
     */
    public String facilityMarks() {
        StringBuilder line = new StringBuilder();
        for (Facility facility : facilities) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(facility.describe());
        }
        return line.toString();
    }

    /**
     * One plugin-level fact about the cluster, as the probe summary shows it.
     *
     * @param name the short name a reader recognises: {@code shovel}, not
     *     {@code rabbitmq_shovel_management}
     * @param present whether it is there
     */
    public record Facility(String name, boolean present) {

        /** {@code shovel✓} or {@code shovel✗}. */
        public String describe() {
            return name + (present ? "✓" : "✗");
        }
    }

    /**
     * Whether a cluster has a capability, and the reason either way.
     *
     * <p>The reason is required even when the answer is yes, because a plan that says a capability
     * is present without saying what was observed is asking to be trusted about the one thing the
     * probe exists to check.
     *
     * @param present whether the cluster has it
     * @param because what was observed
     */
    public record Verdict(boolean present, String because) {
    }

    /**
     * Starts building a probe result.
     *
     * @param name the deployment file's name for the cluster
     * @return a builder
     */
    public static Builder named(String name) {
        return new Builder(name);
    }

    /**
     * Assembles a {@link ProbedCluster}.
     *
     * <p>A builder rather than a nine-argument constructor because both callers benefit and they
     * are very different callers. The RabbitMQ probe fills it in over a dozen management calls,
     * some of which answer "no" — and a test writes three lines and gets a cluster that is
     * realistic in the one respect the test is about.
     */
    public static final class Builder {

        private final String name;
        private final List<Facility> facilities = new ArrayList<>();
        private final Map<Capability, Verdict> capabilities = new EnumMap<>(Capability.class);
        private final List<String> notes = new ArrayList<>();
        private String product = "RabbitMQ";
        private String version = "unknown";
        private Inventory inventory = Inventory.empty();

        private Builder(String name) {
            this.name = name;
        }

        /** What is running. */
        public Builder product(String value) {
            this.product = value;
            return this;
        }

        /** The version it reported. */
        public Builder version(String value) {
            this.version = value;
            return this;
        }

        /** One plugin-level fact, in the order the summary should print it. */
        public Builder facility(String facilityName, boolean present) {
            facilities.add(new Facility(facilityName, present));
            return this;
        }

        /** What was counted. */
        public Builder inventory(Inventory value) {
            this.inventory = value;
            return this;
        }

        /** A capability the cluster has, and what was observed to establish it. */
        public Builder can(Capability capability, String because) {
            capabilities.put(capability, new Verdict(true, because));
            return this;
        }

        /** A capability the cluster does not have, and why not. */
        public Builder cannot(Capability capability, String because) {
            capabilities.put(capability, new Verdict(false, because));
            return this;
        }

        /**
         * Capabilities the cluster has, for a test that cares which ones are present and not about
         * what established them.
         */
        public Builder can(Capability... present) {
            for (Capability capability : present) {
                can(capability, "the probe found it");
            }
            return this;
        }

        /** Something the probe could not determine. */
        public Builder note(String note) {
            notes.add(note);
            return this;
        }

        /** The finished probe result. */
        public ProbedCluster build() {
            return new ProbedCluster(name, product, version, facilities, capabilities, inventory,
                    notes);
        }
    }
}
