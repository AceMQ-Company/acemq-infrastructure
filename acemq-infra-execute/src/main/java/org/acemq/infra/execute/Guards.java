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

import org.acemq.infra.config.WaitFor;
import org.acemq.infra.execute.Observation.Reading;

/**
 * Whether a guard's condition has come true, given what could be seen.
 *
 * <p>Pure arithmetic over a {@link WaitFor} and an {@link Observation}, which is what lets the
 * interesting cases be tested exhaustively with no broker anywhere: a depth that is zero on a
 * cluster whose statistics are five seconds behind, a publish rate that cannot be read at all, a
 * consumer count that is within one bound and outside the other.
 *
 * <h2>Three answers, not two</h2>
 *
 * <p>A guard is normally thought of as true or false, and that is the arrangement that makes a
 * cutover dangerous. The third answer is {@link Verdict#UNOBSERVABLE}, and it is the one that has
 * to exist: a condition nobody can see is not a condition that is false. Treating it as false means
 * the guard waits out its timeout and then does whatever {@code onTimeout} says — which reports
 * "the queues did not empty" about an estate whose queues may well have emptied, and which, with
 * {@code onTimeout: continue}, walks straight past it.
 *
 * <p>So unobservable fails immediately and says which reading was missing and why. The fix is
 * always outside this tool — a permission, a plugin, a statistics collector somebody turned off —
 * and it is worth finding out at second zero rather than at the far end of a fifteen-minute timer.
 *
 * <h2>The statistics database is behind, and a guard that reads it once is wrong</h2>
 *
 * <p>This is the hazard scripts/blue-green-lab.sh wrote down after finding it: the management API's
 * depths come from a statistics database that refreshes on {@code collect_statistics_interval},
 * five seconds by default, rather than on every publish. The lab's own {@code seed} reported zeroes
 * for queues it had demonstrably just filled. Every depth and unacked guard in this tool reads the
 * same lagging number, so a drain is not finished when the API first says zero — it is finished
 * when the API has said zero for longer than the interval.
 *
 * <p>Two things follow, and both are in the executor rather than in the documentation.
 *
 * <ol>
 *   <li><strong>A guard is satisfied by a run of readings, not by one.</strong> The condition has
 *       to hold continuously for {@link #SETTLE}, which is three default intervals. One stale zero
 *       cannot end a drain, because the reading after it will be the real number and the run
 *       starts again.</li>
 *   <li><strong>A timeout shorter than that window is refused before the run starts.</strong>
 *       {@link #tooShortForTheStatistics(WaitFor)} is part of the preflight. A guard that can only
 *       ever pass on a stale reading is a guard that is worse than no guard, and the honest thing
 *       is to refuse it in daylight rather than to honour it at three in the morning.</li>
 * </ol>
 *
 * <p>Waiting longer is not free — it is added to every guarded step of every cutover — and it is
 * still the right trade. The alternative costs messages, and it costs them invisibly.
 */
public final class Guards {

    /**
     * RabbitMQ's default {@code collect_statistics_interval}.
     *
     * <p>Not read from the broker, and that is a decision rather than laziness: the setting is in
     * the {@code rabbit} application environment and the management API does not report it, so
     * anything this could do to "discover" it would be a guess dressed as a measurement. Five
     * seconds is the default a cluster has unless somebody has deliberately changed it, and a
     * cluster that has changed it upward is a cluster whose operator can set the window.
     */
    public static final Duration STATISTICS_INTERVAL = Duration.ofSeconds(5);

    /**
     * How long a condition has to hold before it is believed. Three intervals rather than one,
     * because the interval is when the database refreshes and not when the reading crosses the
     * network, and two refreshes of margin is the difference between confident and lucky.
     */
    public static final Duration SETTLE = STATISTICS_INTERVAL.multipliedBy(3);

    private Guards() {
    }

    /** What a guard's condition turned out to be. */
    public enum Verdict {

        /** Every condition in the guard holds. */
        SATISFIED,

        /** They can all be seen and at least one of them does not hold yet. */
        NOT_YET,

        /** At least one of them cannot be seen, so nothing can be concluded. */
        UNOBSERVABLE
    }

    /**
     * The verdict, and the sentence a report should carry with it.
     *
     * @param verdict what the readings add up to
     * @param describe the conditions and what was actually seen, for the run's report
     */
    public record Answer(Verdict verdict, String describe) {
    }

    /**
     * Checks one guard against one measurement.
     *
     * @param waitFor the guard as the file wrote it
     * @param observation what the cluster looked like
     * @return the verdict, with the readings written out
     */
    public static Answer check(WaitFor waitFor, Observation observation) {
        List<String> seen = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean satisfied = true;

        satisfied &= atMost(waitFor.publishRate(), observation.publishRate(), "publishRate", seen,
                missing);
        satisfied &= atMost(waitFor.unacked(), observation.unacked(), "unacked", seen, missing);
        satisfied &= atMost(waitFor.depth(), observation.depth(), "depth", seen, missing);

        for (WaitFor.Consumers consumers : waitFor.consumers().stream().toList()) {
            satisfied &= atLeast(consumers.min(), observation.consumers(), "consumers", seen,
                    missing);
            satisfied &= atMost(consumers.max(), observation.consumers(), "consumers", seen,
                    missing);
        }

        if (!missing.isEmpty()) {
            return new Answer(Verdict.UNOBSERVABLE, String.join("; ", missing));
        }
        if (seen.isEmpty()) {
            // A guard with no conditions in it waits for nothing, which the validator already
            // refuses in a file. Reaching it here means a step was built in code, and answering
            // "satisfied" would be a silent pass. Saying so costs one line and is not a guess.
            return new Answer(Verdict.UNOBSERVABLE,
                    "the guard names no condition, so there is nothing to observe");
        }
        return new Answer(satisfied ? Verdict.SATISFIED : Verdict.NOT_YET, String.join(", ", seen));
    }

    /**
     * Whether this guard's timeout is short enough that it could pass on a stale reading.
     *
     * @param waitFor the guard
     * @return the refusal to print, or empty when the guard can be trusted
     */
    public static Optional<String> tooShortForTheStatistics(WaitFor waitFor) {
        if (!readsTheStatistics(waitFor)) {
            return Optional.empty();
        }
        Duration timeout = waitFor.timeout().orElse(Duration.ZERO);
        if (timeout.compareTo(SETTLE) >= 0) {
            return Optional.empty();
        }
        return Optional.of("its timeout is shorter than the " + SETTLE.toSeconds() + "s this tool"
                + " waits for the management statistics to settle. The depths and counts a guard"
                + " reads refresh on collect_statistics_interval, so a guard that gives up sooner"
                + " than that can only ever pass on a reading that was already stale when it"
                + " arrived");
    }

    /** Whether anything in this guard comes from the statistics database rather than from a fact. */
    private static boolean readsTheStatistics(WaitFor waitFor) {
        return waitFor.publishRate().isPresent() || waitFor.unacked().isPresent()
                || waitFor.depth().isPresent() || waitFor.consumers().isPresent();
    }

    private static boolean atMost(OptionalInt target, Reading reading, String label,
                                  List<String> seen, List<String> missing) {
        if (target.isEmpty()) {
            return true;
        }
        if (!reading.observed()) {
            missing.add(label + " cannot be observed: " + reading.whyNot());
            return false;
        }
        seen.add(label + "=" + reading.value() + " (wanted <=" + target.getAsInt() + ")");
        return reading.value() <= target.getAsInt();
    }

    private static boolean atLeast(OptionalInt target, Reading reading, String label,
                                   List<String> seen, List<String> missing) {
        if (target.isEmpty()) {
            return true;
        }
        if (!reading.observed()) {
            missing.add(label + " cannot be observed: " + reading.whyNot());
            return false;
        }
        seen.add(label + "=" + reading.value() + " (wanted >=" + target.getAsInt() + ")");
        return reading.value() >= target.getAsInt();
    }
}
