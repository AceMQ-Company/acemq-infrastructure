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
package org.acemq.infra.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.acemq.infra.execute.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a typed line means, and what an untyped one does.
 *
 * <p>Small and worth having. Every question a cutover asks arrives here, and two of them —
 * an {@code onTimeout: prompt} and an {@code endpoint: external} switch — are asked at moments
 * where the next thing that happens cannot be undone. The rule being tested is that the only input
 * which lets a run carry on is the whole word, and that the absence of input is a third answer
 * rather than a yes.
 */
class TerminalTest {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    private Console reading(String... lines) {
        Deque<String> typed = new ArrayDeque<>(List.of(lines));
        return Terminal.reading(new PrintStream(sink, true, StandardCharsets.UTF_8),
                () -> typed.isEmpty() ? null : typed.poll());
    }

    private String said() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("what the word means")
    class TheWord {

        @Test
        @DisplayName("yes, and nothing shorter, carries on")
        void yes() {
            assertThat(Terminal.answerTo("yes")).isEqualTo(Console.Answer.PROCEED);
            assertThat(Terminal.answerTo("YES")).isEqualTo(Console.Answer.PROCEED);
            assertThat(Terminal.answerTo("  yes  ")).isEqualTo(Console.Answer.PROCEED);
        }

        @Test
        @DisplayName("y is what a finger produces on the way to something else")
        void notY() {
            assertThat(Terminal.answerTo("y")).isEqualTo(Console.Answer.STOP);
        }

        @Test
        @DisplayName("the return key on its own stops, which is the answer a stray one should give")
        void emptyStops() {
            assertThat(Terminal.answerTo("")).isEqualTo(Console.Answer.STOP);
            assertThat(Terminal.answerTo("   ")).isEqualTo(Console.Answer.STOP);
        }

        @Test
        @DisplayName("anything else stops")
        void anythingElse() {
            assertThat(Terminal.answerTo("no")).isEqualTo(Console.Answer.STOP);
            assertThat(Terminal.answerTo("yes please")).isEqualTo(Console.Answer.STOP);
            assertThat(Terminal.answerTo("yesterday")).isEqualTo(Console.Answer.STOP);
        }
    }

    @Nested
    @DisplayName("asking")
    class Asking {

        @Test
        @DisplayName("puts the question on the screen and reads one line back")
        void asks() {
            assertThat(reading("yes").ask("Switch the endpoint to green now."))
                    .isEqualTo(Console.Answer.PROCEED);
            assertThat(said()).contains("Switch the endpoint to green now.")
                    .contains("type yes to go on");
        }

        @Test
        @DisplayName("says which way it went, so the transcript is readable afterwards")
        void echoesTheDecision() {
            reading("no").ask("Carry on anyway?");
            assertThat(said()).contains("stopping.");
        }

        @Test
        @DisplayName("end of input is nobody there, which is not the same as being told no")
        void endOfInput() {
            // The distinction that has to survive into the report. A run that stopped because the
            // operator said no and a run that stopped because there was no operator read the same
            // in a log and mean opposite things the morning after, and only one of them says to
            // start it again from a terminal.
            assertThat(reading().ask("Has it been switched?")).isEqualTo(Console.Answer.UNATTENDED);
        }

        @Test
        @DisplayName("answers each question from its own line rather than reusing the first")
        void oneLineEach() {
            Console console = reading("yes", "no");
            assertThat(console.ask("first?")).isEqualTo(Console.Answer.PROCEED);
            assertThat(console.ask("second?")).isEqualTo(Console.Answer.STOP);
            assertThat(console.ask("third?")).isEqualTo(Console.Answer.UNATTENDED);
        }
    }
}
