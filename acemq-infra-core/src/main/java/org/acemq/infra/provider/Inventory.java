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
import java.util.Optional;

/**
 * What a probe counted on one cluster, in one virtual host.
 *
 * <p>A plan that says "14 exchanges, 31 queues, 58 bindings" is worth more than one that says
 * "the topology" for the reason docs/configuration.md gives about the file itself: the person
 * reading it in a pull request was not in the room. A reviewer who knows the estate spots a
 * missing vhost in the counts and spots nothing at all in a sentence.
 *
 * <p>Counts for the things the plan only ever reports as a number, and the actual list for the
 * three it has to reason about. Queues are named because the drain matches patterns against them,
 * and because a stream in the list changes what the plan is allowed to say. Connections are named
 * because the close step's selector matches on their user. Consumers are named because a canary is
 * only safe when every consumer of a scoped queue belongs to a named service, and a connection
 * count cannot answer that — it says somebody is consuming, never what.
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
 * @param consumers who is consuming which queue, or the reason nobody could find out
 */
public record Inventory(int exchanges, int bindings, int users, int permissions, int policies,
                        int operatorPolicies, int parameters, List<Queue> queues,
                        List<Connection> connections, Consumers consumers) {

    public Inventory {
        queues = List.copyOf(queues);
        connections = List.copyOf(connections);
    }

    /** Nothing at all, for the cluster a plan never reads and for a test that does not care. */
    public static Inventory empty() {
        return new Inventory(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Consumers.of(List.of()));
    }

    /**
     * The streams, which are the queues nothing in this tool can move honestly.
     *
     * <p>There is deliberately no {@code depth()} beside this. A plan never wants the whole
     * cluster's depth: it wants the depth of the queues a particular drain's patterns select, and
     * a convenient total is a number that would end up in a plan meaning something subtly other
     * than what it says.
     */
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
        private final List<Consumer> consumers = new ArrayList<>();
        private String consumersUnreadable;
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
        public Builder connection(String name, String user, int consumerCount) {
            connections.add(new Connection(name, user, consumerCount));
            return this;
        }

        /**
         * One consumer, on one queue.
         *
         * @param queue the queue it is attached to
         * @param connection the connection it arrived on, for a message that can be acted on
         * @param user the user that connection authenticated as, which is what a canary's
         *     {@code services:} list is matched against
         * @return this builder
         */
        public Builder consumer(String queue, String connection, String user) {
            consumers.add(new Consumer(queue, connection, user, Optional.empty()));
            return this;
        }

        /**
         * One consumer of a stream, with the position it asked to start from.
         *
         * @param queue the stream it is attached to
         * @param connection the connection it arrived on
         * @param user the user that connection authenticated as
         * @param streamOffset the {@code x-stream-offset} argument as the client wrote it
         * @return this builder
         */
        public Builder streamConsumer(String queue, String connection, String user,
                                      String streamOffset) {
            consumers.add(new Consumer(queue, connection, user,
                    Optional.ofNullable(streamOffset)));
            return this;
        }

        /**
         * Nobody could be listed, and why.
         *
         * <p>The distinction this keeps is the one a canary turns on. A management account that
         * may not read {@code /api/consumers} produces the same empty list as a queue with nothing
         * attached to it, and the two mean opposite things: one says the canary is safe and the
         * other says nobody can tell.
         *
         * @param whyNot what stopped the listing, in words somebody can act on
         * @return this builder
         */
        public Builder consumersUnreadable(String whyNot) {
            this.consumersUnreadable = whyNot;
            return this;
        }

        /** What was counted. */
        public Inventory build() {
            return new Inventory(exchanges, bindings, users, permissions, policies,
                    operatorPolicies, parameters, queues, connections,
                    consumersUnreadable == null ? Consumers.of(consumers)
                            : Consumers.unreadable(consumersUnreadable));
        }
    }

    /**
     * Who is consuming what, or the reason nobody could find out.
     *
     * <p>The same shape as a guard's reading and for the same reason: an absent answer is not an
     * empty one. A canary is safe exactly when every consumer of a scoped queue belongs to a named
     * service, and a listing that could not be taken supports neither that conclusion nor its
     * opposite — so it has to be a third answer rather than a list that happens to have nothing in
     * it.
     *
     * @param observed whether the listing could be taken at all
     * @param all every consumer, meaningless when it could not
     * @param whyNot what stopped it, empty when it was taken
     */
    public record Consumers(boolean observed, List<Consumer> all, String whyNot) {

        public Consumers {
            all = List.copyOf(all);
        }

        /**
         * A listing that was actually taken.
         *
         * @param consumers every consumer in scope
         * @return the listing
         */
        public static Consumers of(List<Consumer> consumers) {
            return new Consumers(true, consumers, "");
        }

        /**
         * A listing that could not be taken, and the sentence to print instead.
         *
         * @param whyNot what stopped it — a permission, most often
         * @return the listing
         */
        public static Consumers unreadable(String whyNot) {
            return new Consumers(false, List.of(), whyNot);
        }

        /**
         * The consumers attached to one queue.
         *
         * @param queue the queue's name
         * @return its consumers, empty when it has none or when the listing was never taken
         */
        public List<Consumer> on(String queue) {
            return all.stream().filter(consumer -> consumer.queue().equals(queue)).toList();
        }
    }

    /**
     * One consumer, on one queue.
     *
     * <p>Modelled beside {@link Connection} rather than folded into it, because a connection
     * consumes an unknown number of queues and the question a canary asks is per-queue. A
     * connection count answers "somebody is consuming"; only this answers "who is consuming
     * {@code orders.notifications}", which is the question that decides whether moving that queue
     * partitions it.
     *
     * @param queue the queue it is attached to
     * @param connection the connection it arrived on, which is what a report names
     * @param user the user that connection authenticated as. A canary's {@code services:} list is
     *     matched against this, for the same reason the close step's selector is: the broker has
     *     no concept of a service, and the user is the only identity it carries from end to end
     * @param streamOffset the {@code x-stream-offset} this consumer asked for, when it is a stream
     *     consumer and the broker reported one. Absent means either not a stream or not stated,
     *     and RabbitMQ reads a stream consumer that states nothing as {@code next}
     */
    public record Consumer(String queue, String connection, String user,
                           Optional<String> streamOffset) {

        public Consumer {
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(streamOffset, "streamOffset");
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
