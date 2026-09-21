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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.config.Action;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.Rollback;
import org.acemq.infra.config.Step;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.yaml.Location;

/**
 * How to get back, derived from what actually happened.
 *
 * <p>Phase 2 builds this and not a later phase, because a cutover without a tested rollback is not
 * finished — docs/roadmap.md says so and it is right. The thing to understand before reading any of
 * the code below is that a rollback is not an undo. docs/blue-green.md puts the failure mode
 * plainly: the drain is a shovel and <strong>a shovel consumes</strong>, so by the time the drain
 * has finished the source's queues are empty. The source is still there, still configured, still
 * accepting connections — and switching the endpoint back on its own hands you a cluster with
 * nothing in it. So the rollback is a second cutover in the other direction, and it costs what the
 * first one cost, twice over: everything that comes back has been republished a second time, so
 * {@code x-delivery-count} has been reset twice and {@code x-death} erased twice.
 *
 * <h2>Derived from what happened, not from what was planned</h2>
 *
 * <p>The list is built from the steps that actually reached {@code done}, in reverse, and that
 * distinction is the whole safety of it. A cutover that aborted at the consumer close has not moved
 * a message; a rollback derived from the <em>plan</em> would drain the target back to the source
 * and find nothing there, having first declared a shovel on a cluster that did not need one. A
 * rollback derived from the run undoes the three steps that ran and stops.
 *
 * <h2>What is deliberately not inverted, and why refusing is right</h2>
 *
 * <ul>
 *   <li><strong>A topology copy.</strong> The inverse of "write the source's shape onto the target"
 *       is not "delete it": the cluster being returned to is the one the shape was read from, so it
 *       already has it, and deleting the target's queues would destroy anything published to it
 *       during the window. There is nothing to undo and pretending otherwise would be the
 *       destructive option.</li>
 *   <li><strong>A connection close.</strong> There is no un-close. Where a client reconnects to is
 *       decided by DNS, a load balancer or a connection string, none of which this tool owns —
 *       docs/message-state.md — so the endpoint switch at the front of the rollback is the whole of
 *       the mechanism, and inventing a step here would suggest otherwise.</li>
 *   <li><strong>A mirror.</strong> Undoing one means tearing down a federation upstream and the
 *       policy that points at it, and the sealed {@link Action} type has no word for that. Adding
 *       one so that this method had something to emit would be inventing a piece of the
 *       configuration format to make a derivation tidy. The rollback says, in a note, that the
 *       federation is still running and where.</li>
 *   <li><strong>An announcement.</strong> A published message cannot be unpublished, and a second
 *       envelope saying something different is a message the file did not write. If a rollback
 *       should announce itself, the file should say so in {@code rollback.steps}.</li>
 * </ul>
 *
 * <p>And the file always wins. When {@code rollback.steps} is written out, that list is used
 * unchanged: a derivation is a default for the case nobody thought about beforehand, and somebody
 * who has thought about it has better information than this method does.
 */
public final class Rollbacks {

    /**
     * What a drain in the other direction waits. The same fifteen minutes the forward default
     * uses, because it is the same operation moving the same backlog back.
     */
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(15);

    private Rollbacks() {
    }

    /**
     * The steps that would undo what a run actually did.
     *
     * @param file the deployment file, read for {@code rollback:}
     * @param completed the steps that reached {@code done}, in the order they ran
     * @param from the cluster the cutover moved away from
     * @param to the cluster it moved to
     * @return the rollback, in the order it should run; empty when nothing that happened needs
     *     undoing
     */
    public static List<Step> derive(DeploymentFile file, List<Step> completed, String from,
                                    String to) {
        Optional<List<Step>> written = file.rollback().flatMap(Rollback::steps);
        if (written.isPresent() && !written.get().isEmpty()) {
            return List.copyOf(written.get());
        }

        String keep = file.rollback().flatMap(Rollback::keep).orElse(from);
        Location where = Location.unknown("the rollback derived from what this run did");

        List<Step> undo = new ArrayList<>();
        for (int index = completed.size() - 1; index >= 0; index--) {
            invert(completed.get(index), keep, from, to, where).ifPresent(undo::add);
        }
        return undo;
    }

    /**
     * Whether a run needs a rollback at all.
     *
     * @param completed the steps that reached {@code done}
     * @return whether any of them moved a message or moved a client
     */
    public static boolean anythingToUndo(List<Step> completed) {
        return completed.stream()
                .anyMatch(step -> step.has(Action.Drain.class) || step.has(Action.Switch.class));
    }

    private static Optional<Step> invert(Step step, String keep, String from, String to,
                                         Location where) {
        Optional<Action.Switch> switched = step.find(Action.Switch.class);
        if (switched.isPresent()) {
            // First in the rollback because it is last in the cutover, and that ordering is the
            // documented one for a reason: bringing the clients back before the messages means
            // they reconnect to a cluster that is empty and stays empty until the drain-back runs,
            // which is a visible outage rather than a silent one.
            return Optional.of(new Step(Optional.of("switch-endpoint"),
                    List.of(new Action.Switch(Optional.of(keep), where)), Optional.empty(),
                    List.of(), where));
        }

        Optional<Action.Drain> drained = step.find(Action.Drain.class);
        if (drained.isPresent()) {
            Action.Drain forward = drained.get();
            String source = forward.to().orElse(to);
            String destination = forward.from().orElse(from);
            return Optional.of(new Step(Optional.of("drain-back"),
                    List.of(new Action.Drain(Optional.of(source), Optional.of(destination),
                            forward.queues(), forward.ackMode(), forward.deleteAfter(), where)),
                    // Waiting on the cluster being emptied, which is now the one the cutover
                    // filled. Abort on timeout, because a rollback that gave up halfway would
                    // leave the messages split across two clusters with nobody holding the list.
                    Optional.of(new WaitFor(Optional.of(source), OptionalInt.empty(),
                            OptionalInt.empty(), OptionalInt.of(0), Optional.empty(),
                            Optional.of(DRAIN_TIMEOUT), Optional.of(OnTimeout.ABORT), where)),
                    List.of(), where));
        }

        return Optional.empty();
    }

    /**
     * The things a report has to say about a rollback that this list cannot express.
     *
     * @param completed the steps that reached {@code done}
     * @param from the cluster the cutover moved away from
     * @return the notes, which may be empty
     */
    public static List<String> notes(List<Step> completed, String from) {
        List<String> notes = new ArrayList<>();
        if (completed.stream().anyMatch(step -> step.has(Action.Drain.class))) {
            notes.add("the drain has run, so " + from + "'s queues are empty. Switching the"
                    + " endpoint back on its own gives you a cluster with nothing in it: the"
                    + " rollback is a second cutover in the other direction.");
            notes.add("anything the rollback brings back has been republished twice, so"
                    + " x-delivery-count has been reset twice and x-death erased twice. A message"
                    + " that was one delivery from being dead-lettered has its attempts back.");
        }
        if (completed.stream().anyMatch(step -> step.has(Action.Mirror.class))) {
            notes.add("a mirror was declared and the rollback does not tear it down: undoing one"
                    + " means removing a federation upstream and the policy pointing at it, and"
                    + " the configuration format has no step that says so. Remove it by hand.");
        }
        return notes;
    }
}
