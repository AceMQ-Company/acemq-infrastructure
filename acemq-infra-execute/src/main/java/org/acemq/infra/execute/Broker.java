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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.config.TopologyPart;

/**
 * The eight verbs that were deliberately absent in phase 1, declared in the change that implements
 * them.
 *
 * <p>docs/broker-agnostic.md lists nine: {@code probe} is the one that only reads and it already
 * lives in {@code org.acemq.infra.provider}, beside the capability set it answers in. The other
 * eight either write or measure something a probe has no reason to look at, and they are here
 * rather than there for exactly that reason — a module that a plan can reach must not contain a
 * method that changes a broker.
 *
 * <p>The verbs sit at the level of <em>intent</em>, which is the decision that page argues for at
 * length. {@link #drain(Drainage)} does not say "declare a shovel"; it says move what is left, and
 * the RabbitMQ implementation is free to be as RabbitMQ-specific as it likes underneath, because a
 * shovel is how that is done on RabbitMQ.
 *
 * <h2>The destructive ones are shaped so they cannot be reached by accident</h2>
 *
 * <p>Three of these do something an estate cannot take back, and the danger with all three is the
 * same: a field left out that reads as "everything". So none of them takes a selector.
 *
 * <ul>
 *   <li>{@link #detach(Attachment, String)} closes <em>one</em> named connection and wants a reason
 *       the client will be told. There is no call that closes a set, so "close everything" is never
 *       one line and never one mistake.</li>
 *   <li>{@link Drainage} refuses to be built with an empty queue list. An empty pattern list means
 *       "all of them" everywhere else in this tool — docs/configuration.md is consistent about that
 *       and {@code Patterns} implements it — and carrying that convention into the one operation
 *       that empties a cluster would mean a missing {@code queues:} shovelled an estate. The
 *       caller resolves its patterns against what is actually there and passes names.</li>
 *   <li>{@link Mirroring} has no queue field at all. Queue federation pulls from its upstream only
 *       when the upstream has no local consumers, so a mirror built from it is an accidental drain
 *       that fires the moment a cutover stops the source's consumers — docs/message-state.md. The
 *       validator refuses to parse it and this seam offers nowhere to put it.</li>
 * </ul>
 */
public interface Broker extends AutoCloseable {

    /** The name the deployment file gave this cluster, which is what every message about it uses. */
    String name();

    /**
     * Reads the configuration of this cluster.
     *
     * @param scope the virtual hosts and the parts of the shape to read
     * @return what was read, as something only the provider can look inside
     */
    Topology snapshotTopology(Scope scope);

    /**
     * Writes a configuration onto this cluster, merging: everything in the snapshot is created or
     * updated and nothing absent from it is removed.
     *
     * @param topology what another cluster turned out to be
     * @param scope the parts to apply, which is how the copy gets split around the drain
     */
    void applyTopology(Topology topology, Scope scope);

    /**
     * Who is connected, and whether they are consuming.
     *
     * @return every client connection, in the broker's order
     */
    List<Attachment> listAttachments();

    /**
     * Makes one client reconnect.
     *
     * <p>This is the step the whole ordering of a cutover is arranged around: a delivery that has
     * not been acknowledged is requeued on <em>this</em> cluster when its connection closes, not on
     * the one the messages are going to.
     *
     * @param attachment the connection to close, as {@link #listAttachments()} reported it
     * @param reason told to the client, and worth making specific
     */
    void detach(Attachment attachment, String reason);

    /**
     * Moves what is left. Messages <strong>leave</strong> the source.
     *
     * <p>Called on the cluster that will host the movement, which for a cutover is the destination.
     * It returns as soon as the movement is declared: a drain is not an operation that finishes
     * inside a method call, and the step waits on a guard afterwards.
     *
     * @param drainage what to move and where from
     * @return a handle naming what was declared, for {@link #finished(Movement)} and
     *     {@link #cancel(Movement)}
     */
    Movement drain(Drainage drainage);

    /**
     * Copies what arrives. Messages <strong>stay</strong> on the source.
     *
     * @param mirroring which exchanges to copy and where from
     * @return a handle naming what was declared
     */
    Movement mirror(Mirroring mirroring);

    /**
     * Whether a movement has finished of its own accord.
     *
     * <p>A drain declared with {@code deleteAfter: queueLength} tears itself down when it has moved
     * the number of messages the queue held at the moment it started, so its absence is a fact
     * about the movement rather than a number from the statistics database. That distinction is
     * what {@link Guards} needs it for.
     *
     * @param movement the handle {@link #drain(Drainage)} or {@link #mirror(Mirroring)} returned
     * @return whether nothing of it remains declared
     */
    boolean finished(Movement movement);

    /**
     * Tears down a movement this run declared.
     *
     * <p>Only ever called with a handle this run was given, which matters: a shovel somebody else
     * declared is somebody else's, and a cutover tool that tidied up the parameters it found would
     * be deleting a stranger's drain halfway through it.
     *
     * @param movement the handle to remove
     */
    void cancel(Movement movement);

    /**
     * Depth, unacked, consumers and publish rate, as far as they can be seen.
     *
     * <p>Every reading is allowed to come back unobservable with a reason, and that is the whole
     * point of the return type. A cutover waits on these numbers, and a provider that answered
     * "nought" where it meant "I cannot see" would turn every guard into a formality.
     *
     * @param queues the queues in scope; empty means every queue in the virtual host, which is safe
     *     here in a way it is not on {@link Drainage} because nothing is being changed
     * @return what could be observed
     */
    Observation measure(List<String> queues);

    /**
     * Tells the clients something is happening.
     *
     * <p>The return value is the half of this that is worth having. An announcement published to an
     * exchange nothing is bound to is accepted by the broker and heard by nobody, and its whole job
     * is to give applications a head start on the guard immediately after it — so a step that
     * reported "published" either way would be reporting the wrong thing about the one case that
     * matters.
     *
     * @param envelope the exchange, the routing key and the body, from {@code deployment.announce}
     * @return whether the broker routed it to at least one queue
     */
    boolean announce(Envelope envelope);

    @Override
    void close();

    /**
     * Which virtual hosts, and which parts of the shape.
     *
     * @param vhosts the virtual hosts in scope; empty means the one the connection is already
     *     scoped to
     * @param parts what to read or write, already resolved from the file's include and exclude
     *     lists. Never empty: a copy of nothing is a step that should not have been planned
     */
    record Scope(List<String> vhosts, List<TopologyPart> parts) {

        public Scope {
            vhosts = List.copyOf(vhosts);
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("a topology scope with no parts in it would"
                        + " read or write nothing. Resolve the step's include and exclude lists"
                        + " before building one.");
            }
        }
    }

    /**
     * One client connection.
     *
     * @param name the broker's name for it, which is what a close addresses
     * @param user the user it authenticated as, which is what a selector matches
     * @param consuming whether closing it would requeue work onto this cluster
     */
    record Attachment(String name, String user, boolean consuming) {

        public Attachment {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(user, "user");
        }
    }

    /**
     * What to move, and where from.
     *
     * @param label the name this run gives the movement, which is how it finds its own again
     * @param sourceUri the source's AMQP URI <em>as the broker running the movement will dial
     *     it</em>. Not the one the operator's laptop uses: this is a broker-side operation
     * @param destinationUri likewise for the destination
     * @param queues the queues to move, by name. Resolved, never patterns, and never empty
     * @param ackMode the movement's acknowledgement mode, kept as the file wrote it
     * @param deleteAfter when the movement tears itself down, likewise
     */
    record Drainage(String label, String sourceUri, String destinationUri, List<String> queues,
                    String ackMode, String deleteAfter) {

        public Drainage {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(sourceUri, "sourceUri");
            Objects.requireNonNull(destinationUri, "destinationUri");
            queues = List.copyOf(queues);
            // The one guard rail that has to be in the type rather than in the caller. Everywhere
            // else an empty list means all of them; here that convention would mean a file which
            // forgot `queues:` emptied a cluster, and a shovel cannot be taken back.
            if (queues.isEmpty()) {
                throw new IllegalArgumentException("a drain with no queues in it would move"
                        + " nothing, and a drain that treated that as 'all of them' would empty a"
                        + " cluster because a field was left out. Resolve the patterns first and"
                        + " pass the names.");
            }
        }
    }

    /**
     * What to copy, and where from.
     *
     * <p>Exchanges, and the word is the whole design. There is no queue field here and adding one
     * would be the single most expensive mistake available in this domain — see {@link Broker} and
     * docs/message-state.md.
     *
     * @param label the name this run gives the movement
     * @param sourceUri the upstream's AMQP URI, as the copying broker will dial it
     * @param exchanges the exchanges to federate. Never empty
     * @param prefetch how many messages the link takes at a time
     * @param ackMode the link's acknowledgement mode, kept as written
     */
    record Mirroring(String label, String sourceUri, List<String> exchanges, OptionalInt prefetch,
                     Optional<String> ackMode) {

        public Mirroring {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(sourceUri, "sourceUri");
            exchanges = List.copyOf(exchanges);
            if (exchanges.isEmpty()) {
                throw new IllegalArgumentException("a mirror with no exchanges named would"
                        + " federate nothing. The file has to say what is being copied.");
            }
        }
    }

    /**
     * A movement this run declared, and the pieces it is made of.
     *
     * @param label the name this run gave it
     * @param on the cluster it was declared on
     * @param parts what the provider actually created, for a report that has to be actionable when
     *     a run aborts with one still running
     */
    record Movement(String label, String on, List<String> parts) {

        public Movement {
            parts = List.copyOf(parts);
        }

        /** {@code lab-drain on green: shovel orders.new, shovel orders.audit}. */
        public String describe() {
            return label + " on " + on + (parts.isEmpty() ? "" : ": " + String.join(", ", parts));
        }
    }

    /**
     * The announcement.
     *
     * @param exchange where to publish
     * @param routingKey under what key
     * @param payload the body, as the file wrote it
     */
    record Envelope(String exchange, String routingKey, String payload) {

        public Envelope {
            Objects.requireNonNull(exchange, "exchange");
            Objects.requireNonNull(routingKey, "routingKey");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
