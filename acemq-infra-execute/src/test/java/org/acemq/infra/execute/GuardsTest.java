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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.config.OnTimeout;
import org.acemq.infra.config.WaitFor;
import org.acemq.infra.execute.Observation.Reading;
import org.acemq.infra.yaml.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic a cutover's safety rests on, with no broker in it.
 *
 * <p>Every case here is one an estate produces and a container makes a nuisance to arrange: a
 * publish rate nobody can read because the metrics collector is off, a depth that is within its
 * bound and an unacked count that is not, a guard whose timeout is shorter than the interval the
 * numbers it reads refresh on.
 */
class GuardsTest {

    private static final Location WHERE = Location.unknown("a test");

    private static WaitFor guard(OptionalInt publishRate, OptionalInt unacked, OptionalInt depth,
                                 Optional<WaitFor.Consumers> consumers, Duration timeout) {
        return new WaitFor(Optional.of("blue"), publishRate, unacked, depth, consumers,
                Optional.of(timeout), Optional.of(OnTimeout.ABORT), WHERE);
    }

    private static Observation seeing(Reading depth, Reading unacked, Reading consumers,
                                      Reading publishRate) {
        return new Observation(depth, unacked, consumers, publishRate);
    }

    @Nested
    @DisplayName("a condition that can be seen")
    class Seen {

        @Test
        void isSatisfiedWhenTheNumberIsWithinItsBound() {
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0),
                            Optional.empty(), Duration.ofMinutes(15)),
                    seeing(Reading.of(0), Reading.of(0), Reading.of(0), Reading.of(0)));

            assertThat(answer.verdict()).isEqualTo(Guards.Verdict.SATISFIED);
            assertThat(answer.describe()).isEqualTo("depth=0 (wanted <=0)");
        }

        @Test
        void isNotYetWhenItIsNot() {
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0),
                            Optional.empty(), Duration.ofMinutes(15)),
                    seeing(Reading.of(41), Reading.of(0), Reading.of(0), Reading.of(0)));

            assertThat(answer.verdict()).isEqualTo(Guards.Verdict.NOT_YET);
            assertThat(answer.describe()).contains("depth=41");
        }

        @Test
        void answersEveryConditionAndNotOnlyTheFirstToFail() {
            // The report is read by somebody deciding whether to carry on, and "depth is still 41"
            // on its own invites the conclusion that everything else has settled.
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.of(0), OptionalInt.of(0), OptionalInt.of(0),
                            Optional.empty(), Duration.ofMinutes(15)),
                    seeing(Reading.of(41), Reading.of(7), Reading.of(0), Reading.of(3)));

            assertThat(answer.describe())
                    .contains("publishRate=3").contains("unacked=7").contains("depth=41");
        }

        @Test
        void checksBothEndsOfAConsumerCount() {
            WaitFor both = guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                    Optional.of(new WaitFor.Consumers(OptionalInt.of(1), OptionalInt.of(4), WHERE)),
                    Duration.ofMinutes(5));

            assertThat(Guards.check(both, seeing(Reading.of(0), Reading.of(0), Reading.of(2),
                    Reading.of(0))).verdict()).isEqualTo(Guards.Verdict.SATISFIED);
            assertThat(Guards.check(both, seeing(Reading.of(0), Reading.of(0), Reading.of(0),
                    Reading.of(0))).verdict()).isEqualTo(Guards.Verdict.NOT_YET);
            assertThat(Guards.check(both, seeing(Reading.of(0), Reading.of(0), Reading.of(9),
                    Reading.of(0))).verdict()).isEqualTo(Guards.Verdict.NOT_YET);
        }
    }

    @Nested
    @DisplayName("a condition that cannot be seen")
    class Unseen {

        @Test
        void isNotTreatedAsFalse() {
            // The whole point of the third verdict. Read as false, this guard would wait out its
            // fifteen minutes and then report that the queues did not empty -- about an estate
            // whose queues may well have emptied.
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.of(0), OptionalInt.empty(), OptionalInt.empty(),
                            Optional.empty(), Duration.ofMinutes(2)),
                    seeing(Reading.of(0), Reading.of(0), Reading.of(0),
                            Reading.unobservable("rates_mode is none")));

            assertThat(answer.verdict()).isEqualTo(Guards.Verdict.UNOBSERVABLE);
            assertThat(answer.describe())
                    .isEqualTo("publishRate cannot be observed: rates_mode is none");
        }

        @Test
        void isIgnoredWhenTheGuardDoesNotAskAboutIt() {
            // A publish rate nobody can read does not stop a guard that was waiting on depth.
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0),
                            Optional.empty(), Duration.ofMinutes(15)),
                    seeing(Reading.of(0), Reading.of(0), Reading.of(0),
                            Reading.unobservable("rates_mode is none")));

            assertThat(answer.verdict()).isEqualTo(Guards.Verdict.SATISFIED);
        }

        @Test
        void isWhatAGuardWithNoConditionsIs() {
            Guards.Answer answer = Guards.check(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                            Optional.empty(), Duration.ofMinutes(5)),
                    seeing(Reading.of(0), Reading.of(0), Reading.of(0), Reading.of(0)));

            assertThat(answer.verdict()).isEqualTo(Guards.Verdict.UNOBSERVABLE);
            assertThat(answer.describe()).contains("names no condition");
        }
    }

    @Nested
    @DisplayName("a timeout shorter than the statistics interval")
    class TooShort {

        @Test
        void isRefused() {
            Optional<String> refusal = Guards.tooShortForTheStatistics(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0),
                            Optional.empty(), Duration.ofSeconds(5)));

            assertThat(refusal).isPresent();
            assertThat(refusal.get()).contains("collect_statistics_interval");
        }

        @Test
        void isAcceptedAtExactlyTheWindow() {
            assertThat(Guards.tooShortForTheStatistics(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0),
                            Optional.empty(), Guards.SETTLE))).isEmpty();
        }

        @Test
        void doesNotApplyToAGuardThatReadsNoStatistics() {
            // Nothing in this guard comes from the statistics database, so there is no stale
            // reading for it to pass on. Refusing it would be refusing a file for no reason.
            assertThat(Guards.tooShortForTheStatistics(
                    guard(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                            Optional.empty(), Duration.ofSeconds(1)))).isEmpty();
        }
    }
}
