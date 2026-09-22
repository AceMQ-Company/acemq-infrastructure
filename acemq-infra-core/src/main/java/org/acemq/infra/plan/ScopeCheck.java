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
package org.acemq.infra.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acemq.infra.config.Deployment;
import org.acemq.infra.provider.Inventory;

/**
 * Whether a canary's scope is closed, which is the only reason a canary is safe.
 *
 * <p>docs/canary.md is a long argument that a broker canary is not a traffic split, and the whole
 * of what makes it not one is this check. "Move one service, completely" and "the unit is a queue
 * and everything attached to it" are not descriptions of intent — they are a property somebody has
 * to verify against the live estate, because the estate is where the consumer nobody remembered is
 * attached. Move a queue without one of its consumers and you have built the partition the page is
 * about: the queue exists on both clusters, each holds a fraction of the messages, and a consumer
 * on one cannot see the other's.
 *
 * <h2>The check runs in both directions, and the second one is not in the page</h2>
 *
 * <p>The documented rule is the forward one: every consumer of a scoped queue must be one of the
 * named services. Written down on its own it leaves the mirror image open, and the mirror image
 * costs exactly as much.
 *
 * <p>Suppose {@code notification-service} consumes {@code orders.notifications}, which is in scope,
 * and also {@code orders.new}, which is not. The forward check passes: every consumer of every
 * scoped queue is a named service. The cutover then closes that service's connections and its
 * endpoint resolves to the target — so {@code orders.new} is left on the source with nothing
 * consuming it, while the service sits on the target watching a copy of {@code orders.new} that
 * the topology import created and that nothing will ever publish to. The queue is not split; it is
 * abandoned, which is worse, and it is silent in exactly the same way.
 *
 * <p>So the condition this enforces is that the scope is <strong>closed</strong>: the scoped queues
 * and the named services form a component with no edge leaving it. That is what "a queue and
 * everything attached to it" means when it is taken seriously, and both halves refuse.
 *
 * <h2>A service is a broker user</h2>
 *
 * <p>The page never says what a name in {@code services:} is matched against, and there is only one
 * honest answer available: the broker has no concept of a service, and the user a connection
 * authenticated as is the only identity it carries from the client to the management API. It is
 * also already the answer everywhere else — {@link DefaultSteps} passes {@code scope.services}
 * straight into the close step's {@code select.users}, and examples/canary.yaml writes
 * {@code notification-service} in both places. Matching on anything else here would mean the check
 * and the step it protects disagreed about who the service is.
 *
 * <h2>What it does when it cannot tell</h2>
 *
 * <p>It refuses, and that is the decision worth defending. A management account that may not read
 * the consumer listing produces an empty list, and an empty list is indistinguishable from a queue
 * with nothing attached — one of which says the canary is safe and the other of which says nobody
 * knows. {@link Inventory.Consumers} keeps the two apart precisely so that this method can refuse
 * the second, because a check that silently passed when it could not see would be worse than no
 * check: it would be a check somebody had read the output of.
 */
public final class ScopeCheck {

    private ScopeCheck() {
    }

    /**
     * What the scope turned out to be, on a cluster.
     *
     * @param refusals reasons the canary must not run as written; empty means it may
     * @param warnings things that are true and worth saying, none of which stop it
     * @param lines what the scope resolved to, for the plan and the run report
     */
    public record Result(List<String> refusals, List<String> warnings, List<String> lines) {

        public Result {
            refusals = List.copyOf(refusals);
            warnings = List.copyOf(warnings);
            lines = List.copyOf(lines);
        }

        /** Whether the canary may go ahead. */
        public boolean ok() {
            return refusals.isEmpty();
        }
    }

    /**
     * Checks a canary's scope against what a cluster turned out to be.
     *
     * @param scope the {@code deployment.scope} block
     * @param cluster the name the file gave the cluster being moved away from, for the messages
     * @param inventory what the probe found on it
     * @return the refusals, the warnings, and what the scope resolved to
     */
    public static Result of(Deployment.Scope scope, String cluster, Inventory inventory) {
        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        List<String> scoped = Patterns.select(inventory.queues(), scope.queues(),
                Inventory.Queue::name).stream().map(Inventory.Queue::name).toList();
        Set<String> inScope = new LinkedHashSet<>(scoped);
        Set<String> services = new LinkedHashSet<>(scope.services());

        lines.add(Text.count(scoped.size(), "queue") + " in scope on " + cluster
                + (scoped.isEmpty() ? "" : ": " + String.join(", ", scoped)));
        missingByName(scope, inventory, cluster).forEach(warnings::add);

        Inventory.Consumers consumers = inventory.consumers();
        if (!consumers.observed()) {
            // The refusal that keeps the whole operation honest. Everything below this line is an
            // argument about a list, and there is no list.
            refusals.add("the consumers of " + cluster + " could not be listed, so the one check"
                    + " that makes a canary safe cannot be made: " + consumers.whyNot()
                    + ". A canary moves a queue and everything attached to it, and this run cannot"
                    + " see what is attached. Give the management user permission to read the"
                    + " consumer listing and run it again.");
            lines.add("consumers on " + cluster + ": UNREADABLE — " + consumers.whyNot());
            return new Result(refusals, warnings, lines);
        }

        strangers(consumers, inScope, services, cluster).forEach(refusals::add);
        elsewhere(consumers, inScope, services, cluster).forEach(refusals::add);

        long attached = consumers.all().stream()
                .filter(consumer -> inScope.contains(consumer.queue())).count();
        lines.add(Text.count(attached, "consumer") + " attached to them"
                + (services.isEmpty() ? "" : ", scope.services names "
                        + String.join(", ", services)));

        idle(consumers, scoped, cluster).forEach(warnings::add);
        absent(consumers, services, inScope, cluster).forEach(warnings::add);
        return new Result(refusals, warnings, lines);
    }

    /**
     * Consumers of a scoped queue that are not one of the named services.
     *
     * <p>The documented refusal, grouped by user so that a service with forty connections on the
     * queue produces one sentence rather than forty. The connections are still named, up to a
     * handful, because "something else is attached" is not actionable and
     * {@code 10.0.0.7:51022 -> …} is.
     */
    private static List<String> strangers(Inventory.Consumers consumers, Set<String> inScope,
                                          Set<String> services, String cluster) {
        Map<String, List<Inventory.Consumer>> byUser = new LinkedHashMap<>();
        for (Inventory.Consumer consumer : consumers.all()) {
            if (inScope.contains(consumer.queue()) && !services.contains(consumer.user())) {
                byUser.computeIfAbsent(consumer.user(), user -> new ArrayList<>()).add(consumer);
            }
        }
        List<String> refusals = new ArrayList<>();
        byUser.forEach((user, attached) -> refusals.add("'" + user + "' is consuming "
                + names(attached, Inventory.Consumer::queue) + " on " + cluster
                + " and deployment.scope.services does not name it ("
                + connections(attached) + "). Moving a queue without one of its consumers"
                + " partitions it: the queue would exist on both clusters, each holding a fraction"
                + " of the messages, with no mechanism by which either consumer could see the"
                + " other's. Add '" + user + "' to services if it moves with the workload, or take"
                + " its queues out of scope.queues if it does not (docs/canary.md)."));
        return refusals;
    }

    /**
     * Queues a named service consumes that the scope did not name.
     *
     * <p>The undocumented half, and the reason it refuses rather than warns is that its failure is
     * quieter than the documented one. A partitioned queue at least has messages arriving on both
     * sides; an abandoned queue has a service watching an empty copy on the target while the real
     * one fills up on the source, and nothing about either cluster looks wrong.
     */
    private static List<String> elsewhere(Inventory.Consumers consumers, Set<String> inScope,
                                          Set<String> services, String cluster) {
        Map<String, List<Inventory.Consumer>> byUser = new LinkedHashMap<>();
        for (Inventory.Consumer consumer : consumers.all()) {
            if (services.contains(consumer.user()) && !inScope.contains(consumer.queue())) {
                byUser.computeIfAbsent(consumer.user(), user -> new ArrayList<>()).add(consumer);
            }
        }
        List<String> refusals = new ArrayList<>();
        byUser.forEach((user, attached) -> refusals.add("'" + user + "' is named in"
                + " deployment.scope.services and is also consuming "
                + names(attached, Inventory.Consumer::queue) + " on " + cluster + ", which"
                + " scope.queues does not select. This canary moves the service and leaves those"
                + " queues behind, so they end the cutover on " + cluster + " with nothing"
                + " consuming them while the service watches an empty copy on the other cluster."
                + " Either add them to scope.queues or move that workload separately."));
        return refusals;
    }

    /** Scoped queues nothing is consuming, which is legal and is worth a reader knowing. */
    private static List<String> idle(Inventory.Consumers consumers, List<String> scoped,
                                     String cluster) {
        List<String> quiet = scoped.stream()
                .filter(queue -> consumers.on(queue).isEmpty()).toList();
        if (quiet.isEmpty()) {
            return List.of();
        }
        return List.of("nothing is consuming " + String.join(", ", quiet) + " on " + cluster + "."
                + " That is safe to move — there is no consumer to leave behind — but a canary is"
                + " meant to be a workload somebody is watching, and a queue with no consumer is a"
                + " workload that is not running.");
    }

    /**
     * Services the file names that are consuming nothing in scope.
     *
     * <p>A warning rather than a refusal, and a valuable one: the same string is the close step's
     * {@code select.users}, so a misspelled service name is a close step that closes nothing and a
     * cutover that moves the messages out from under a consumer still attached to the source.
     */
    private static List<String> absent(Inventory.Consumers consumers, Set<String> services,
                                       Set<String> inScope, String cluster) {
        List<String> unseen = services.stream()
                .filter(service -> consumers.all().stream().noneMatch(consumer ->
                        consumer.user().equals(service) && inScope.contains(consumer.queue())))
                .toList();
        if (unseen.isEmpty()) {
            return List.of();
        }
        return List.of("deployment.scope.services names " + String.join(", ", unseen)
                + " and nothing by that name is consuming any queue in scope on " + cluster + "."
                + " The same names are what the close step selects on, so either the workload is"
                + " not running or the name does not match the user its connections authenticate"
                + " as — and in the second case the close step will close nothing.");
    }

    /**
     * Queues the scope names literally that the cluster does not have.
     *
     * <p>Only the literal entries. A pattern that selects nothing may well be a pattern that will
     * select something tomorrow, whereas {@code orders.notifications} written out by hand and
     * absent from the estate is a typo or the wrong vhost.
     */
    private static List<String> missingByName(Deployment.Scope scope, Inventory inventory,
                                              String cluster) {
        Set<String> present = new LinkedHashSet<>(
                inventory.queues().stream().map(Inventory.Queue::name).toList());
        List<String> missing = scope.queues().stream()
                .filter(pattern -> !pattern.startsWith("!"))
                .filter(pattern -> pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0)
                .filter(pattern -> !present.contains(pattern))
                .toList();
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of("deployment.scope.queues names " + String.join(", ", missing)
                + " and " + cluster + " has no queue by that name in "
                + scope.vhost().orElse("the vhost the file gave this cluster")
                + ". Written out by hand rather than matched by a pattern, so this is a spelling or"
                + " a vhost rather than a queue that has not been declared yet.");
    }

    private static String names(List<Inventory.Consumer> consumers,
                                java.util.function.Function<Inventory.Consumer, String> of) {
        return String.join(", ", new LinkedHashSet<>(consumers.stream().map(of).toList()));
    }

    /** Up to three connections by name, and a count for the rest. Forty names help nobody. */
    private static String connections(List<Inventory.Consumer> consumers) {
        List<String> all = List.copyOf(new LinkedHashSet<>(
                consumers.stream().map(Inventory.Consumer::connection).toList()));
        if (all.size() <= 3) {
            return String.join(", ", all);
        }
        return String.join(", ", all.subList(0, 3)) + " and " + (all.size() - 3) + " more";
    }
}
