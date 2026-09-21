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
import java.util.List;
import java.util.Objects;

/**
 * What a probe counted on one cluster, in one virtual host.
 *
 * <p>A plan that says "14 exchanges, 31 queues, 58 bindings" is worth more than one that says
 * "the topology" for the reason docs/configuration.md gives about the file itself: the person
 * reading it in a pull request was not in the room. A reviewer who knows the estate spots a
 * missing vhost in the counts and spots nothing at all in a sentence.
 *
 * <p>Counts for the things the plan only ever reports as a number, and the actual list for the two
 * it has to reason about. Queues are named because the drain matches patterns against them, and
 * because a stream in the list changes what the plan is allowed to say. Connections are named
 * because the close step's selector matches on their user.
 *
 * @param exchanges how many exchanges are in scope
 * @param bindings how many bindings
 * @param users how many users the cluster has, which is not scoped to a vhost
 * @param permissions how many permission entries
 * @param policies how many policies, which are the ones that must land after the drain
 * @param operatorPolicies how many operator policies, which an application cannot override
 * @param parameters how many runtime parameters — the federation upstreams and the shovels, which
 *     a topology copy that only looked at queues and exchanges would leave behind
 * @param queues every queue in scope, with its depth and its type
 * @param connections every client connection, with the user it authenticated as
 */
public record Inventory(int exchanges, int bindings, int users, int permissions, int policies,
                        int operatorPolicies, int parameters, List<Queue> queues,
                        List<Connection> connections) {

    public Inventory {
        queues = List.copyOf(queues);
        connections = List.copyOf(connections);
    }

    /** Nothing at all, for the cluster a plan never reads and for a test that does not care. */
    public static Inventory empty() {
        return new Inventory(0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** How many messages are sitting in the queues in scope. */
    public long depth() {
        return queues.stream().mapToLong(Queue::messages).sum();
    }

    /** The streams, which are the queues nothing in this tool can move honestly. */
    public List<Queue> streams() {
        return queues.stream().filter(Queue::isStream).toList();
    }

    /**
     * Starts counting.
     *
     * @return a builder, with everything at zero
     */
    public static Builder counting() {
        return new Builder();
    }

    /**
     * Assembles an {@link Inventory}.
     *
     * <p>Nine positional fields, six of which are integers that mean different things, is a
     * constructor call nobody can read back — and the two callers who write one are a probe
     * filling it in over a dozen management calls and a test that cares about two of the nine.
     */
    public static final class Builder {

        private final List<Queue> queues = new ArrayList<>();
        private final List<Connection> connections = new ArrayList<>();
        private int exchanges;
        private int bindings;
        private int users;
        private int permissions;
        private int policies;
        private int operatorPolicies;
        private int parameters;

        private Builder() {
        }

        /** How many exchanges are in scope. */
        public Builder exchanges(int many) {
            this.exchanges = many;
            return this;
        }

        /** How many bindings. */
        public Builder bindings(int many) {
            this.bindings = many;
            return this;
        }

        /** How many users, which are a property of the cluster rather than of the vhost. */
        public Builder users(int many) {
            this.users = many;
            return this;
        }

        /** How many permission entries. */
        public Builder permissions(int many) {
            this.permissions = many;
            return this;
        }

        /** How many policies. */
        public Builder policies(int many) {
            this.policies = many;
            return this;
        }

        /** How many operator policies. */
        public Builder operatorPolicies(int many) {
            this.operatorPolicies = many;
            return this;
        }

        /** How many runtime parameters. */
        public Builder parameters(int many) {
            this.parameters = many;
            return this;
        }

        /** One queue, with its depth and its type. */
        public Builder queue(String name, String type, long messages, int consumers) {
            queues.add(new Queue(name, type, messages, consumers));
            return this;
        }

        /** One connection, with the user it authenticated as. */
        public Builder connection(String name, String user, int consumers) {
            connections.add(new Connection(name, user, consumers));
            return this;
        }

        /** What was counted. */
        public Inventory build() {
            return new Inventory(exchanges, bindings, users, permissions, policies,
                    operatorPolicies, parameters, queues, connections);
        }
    }

    /**
     * One queue.
     *
     * <p>{@code type} is the broker's word — {@code classic}, {@code quorum}, {@code stream} —
     * rather than an enum, because a broker that grows a fourth kind should show up in a plan as
     * its name rather than as a parse failure in a tool that only reads.
     *
     * @param name the queue's name
     * @param type what kind of queue it is
     * @param messages how many messages it holds, ready and unacknowledged together
     * @param consumers how many consumers are attached
     */
    public record Queue(String name, String type, long messages, int consumers) {

        public Queue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        /**
         * Whether this is a stream, which is the question that decides whether a plan may proceed
         * quietly — docs/message-state.md. A stream's consumers hold offsets into a specific log
         * and there is no mechanism, at any version, for writing those into another cluster's.
         */
        public boolean isStream() {
            return "stream".equals(type);
        }
    }

    /**
     * One client connection.
     *
     * <p>{@code consumers} is how the {@code role: consumer} selector is answered without
     * inventing a role concept the broker does not have: a connection with consumers on it is
     * consuming, and one without is publishing or idle.
     *
     * @param name the broker's name for the connection, which is what a close would address
     * @param user the user it authenticated as, which is what a selector matches
     * @param consumers how many consumers are attached across its channels
     */
    public record Connection(String name, String user, int consumers) {

        public Connection {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(user, "user");
        }

        /** Whether closing this connection would requeue work onto the source. */
        public boolean consuming() {
            return consumers > 0;
        }
    }
}
