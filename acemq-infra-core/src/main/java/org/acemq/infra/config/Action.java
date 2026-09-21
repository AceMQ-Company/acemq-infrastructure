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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.yaml.Location;

/**
 * The one thing a step does.
 *
 * <p>Sealed, so that the planner can switch over every action and the compiler says when a new one
 * is added and somewhere has not handled it. A step carries exactly one of these, optionally with
 * a guard beside it — that pairing is what makes the plan output readable, because every line is
 * an action and the line under it is what it waits for.
 *
 * <p>{@link Drain} and {@link Mirror} look similar and are opposites, which is the single easiest
 * thing to get wrong in this domain. A drain is a shovel: messages <em>leave</em> the source. A
 * mirror is exchange federation: messages <em>stay</em>. They are separate types rather than one
 * type with a {@code mechanism:} field for exactly that reason — docs/message-state.md.
 */
public sealed interface Action {

    /** Where the action's key is written. */
    Location location();

    /** The key this action is written under, for messages and for the plan output. */
    String keyword();

    /**
     * {@code requires:} — the capabilities that must be present before anything happens.
     *
     * <p>Asserted by {@code probe} against what the clusters turned out to be able to do. The
     * value of stating them in the file is that "blue has rabbitmq_shovel disabled, so step
     * drain-messages cannot run" arrives before the first write rather than at step four with half
     * an estate moved.
     *
     * @param capabilities what this deployment needs
     * @param location where {@code requires:} is written
     */
    record Requires(List<Capability> capabilities, Location location) implements Action {
        @Override
        public String keyword() {
            return "requires";
        }
    }

    /**
     * {@code copyTopology:} — write one cluster's shape onto another, merging.
     *
     * <p>{@code include} and {@code exclude} are lists rather than a single selection because the
     * copy has to be splittable. An empty {@code include} means everything, which is what makes
     * the {@code exclude} on the first half of a split copy the thing that saves the backlog.
     *
     * @param from the cluster to read
     * @param to the cluster to write
     * @param vhosts which vhosts are in scope
     * @param include the parts to copy; empty means all of them
     * @param exclude the parts to leave behind
     * @param location where {@code copyTopology:} is written
     */
    record CopyTopology(Optional<String> from, Optional<String> to, List<String> vhosts,
                        List<TopologyPart> include, List<TopologyPart> exclude,
                        Location location) implements Action {
        @Override
        public String keyword() {
            return "copyTopology";
        }
    }

    /**
     * {@code announce:} — publish the deployment envelope described by {@code deployment.announce}.
     *
     * <p>Carries nothing of its own. The envelope — exchange, routing key, payload — was the best
     * idea in the format this inherits and it is written once, at the deployment, so that a file
     * with three announcements in it cannot have three different opinions about what is happening.
     *
     * @param location where {@code announce:} is written
     */
    record Announce(Location location) implements Action {
        @Override
        public String keyword() {
            return "announce";
        }
    }

    /**
     * {@code closeConnections:} — make a set of clients reconnect.
     *
     * <p>This is the step that produces the requeue the whole ordering of a cutover is arranged
     * around: unacked deliveries go back onto the <em>source</em> when their connection closes.
     *
     * @param on which cluster to close connections on
     * @param select who to close
     * @param after the condition to reach before moving on
     * @param location where {@code closeConnections:} is written
     */
    record CloseConnections(Optional<String> on, Optional<Selector> select, Optional<After> after,
                            Location location) implements Action {
        @Override
        public String keyword() {
            return "closeConnections";
        }

        /**
         * Who to close.
         *
         * <p>{@code role} is a free string rather than an enum because
         * docs/configuration.md shows {@code consumer} and never enumerates the alternatives.
         * Guessing at the closed set here would mean refusing a spelling the format may well
         * allow, and this parser only closes a vocabulary the documentation has closed.
         *
         * @param users the users whose connections are in scope
         * @param role what those connections are doing
         * @param location where {@code select:} is written
         */
        record Selector(List<String> users, Optional<String> role, Location location) {
        }

        /**
         * The condition to reach before the step is done.
         *
         * <p>The same three fields a {@link WaitFor} ends with, on the action rather than beside
         * it, because closing a connection and waiting for what it was holding to settle is one
         * operation rather than two.
         *
         * @param unacked wait until nothing is held unacknowledged
         * @param timeout how long to wait
         * @param onTimeout what to do when it expires
         * @param location where {@code after:} is written
         */
        record After(OptionalInt unacked, Optional<Duration> timeout, Optional<OnTimeout> onTimeout,
                     Location location) {
        }
    }

    /**
     * {@code drain:} — a shovel. Messages <strong>leave</strong> the source.
     *
     * <p>Source-destructive, which is correct here and wrong almost everywhere else. After this
     * step the rollback for anything moved is a drain in the other direction, not a switch back —
     * and a shovel republishes, so {@code x-delivery-count} resets and {@code x-death} is erased
     * on everything it moves.
     *
     * @param from the cluster to consume from
     * @param to the cluster to publish to
     * @param queues the queue patterns in scope, where a leading {@code !} excludes
     * @param ackMode the shovel's acknowledgement mode, kept as written for the same reason
     *     {@code role} is
     * @param deleteAfter when the shovel tears itself down
     * @param location where {@code drain:} is written
     */
    record Drain(Optional<String> from, Optional<String> to, List<String> queues,
                 Optional<String> ackMode, Optional<String> deleteAfter,
                 Location location) implements Action {
        @Override
        public String keyword() {
            return "drain";
        }
    }

    /**
     * {@code mirror:} — exchange federation. Messages <strong>stay</strong> on the source.
     *
     * <p>{@code queues} is modelled even though it is always wrong, because the validator has to
     * be able to say why it is wrong. A federated queue pulls from its upstream only when the
     * upstream has no local consumers, which makes a mirror built from it an accidental drain that
     * fires at the exact moment a cutover stops the source's consumers.
     *
     * @param from the upstream cluster
     * @param to the cluster that receives the copy
     * @param exchanges the exchanges to federate
     * @param queues present only so that asking for queue federation can be refused with a reason
     * @param location where {@code mirror:} is written
     */
    record Mirror(Optional<String> from, Optional<String> to, List<String> exchanges,
                  List<String> queues, Location location) implements Action {
        @Override
        public String keyword() {
            return "mirror";
        }
    }

    /**
     * {@code endpoint:} — switch clients to a cluster, through whatever the top-level
     * {@code endpoint:} block says.
     *
     * @param target the cluster clients should reach after this step
     * @param location where the step's {@code endpoint:} is written
     */
    record Switch(Optional<String> target, Location location) implements Action {
        @Override
        public String keyword() {
            return "endpoint";
        }
    }
}
