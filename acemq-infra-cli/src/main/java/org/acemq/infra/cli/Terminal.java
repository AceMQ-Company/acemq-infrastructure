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

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Supplier;

import org.acemq.infra.execute.Console;

/**
 * The human, when there is one, and nothing pretending to be one when there is not.
 *
 * <p>Two steps in a cutover stop and ask — a guard whose {@code onTimeout} is {@code prompt}, and an
 * {@code endpoint: external} switch — and {@link Console} already says what the three answers mean.
 * What is decided here is the question underneath: <em>is anybody there</em>. It is decided in one
 * place, by asking the runtime, and there is deliberately no way to answer it from the command line.
 *
 * <p>That last clause is the whole design. A flag that made a prompt answer itself would be a flag
 * that turns the two steps a cutover stops at into two steps it walks past, and it would be reached
 * for at three in the morning by somebody who wanted the pipeline to finish. So {@code --yes} on
 * {@link Cli} consents to <em>starting</em> a cutover and consents to nothing after it: every
 * question a run asks once it is running is answered by this class or by {@link Console#unattended},
 * and which of the two is in play is a fact about the process rather than about its arguments.
 *
 * <p>The answer has to be the word {@code yes}, spelled out. {@code y} is what a finger produces on
 * the way to something else, and this is asked at the moments where the next thing that happens
 * cannot be taken back.
 */
final class Terminal {

    private Terminal() {
    }

    /**
     * The console this process actually has.
     *
     * @param out where the run's narration goes
     * @return a console that reads the terminal, or {@link Console#unattended} when there is no
     *     terminal to read
     */
    static Console on(PrintStream out) {
        java.io.Console console = System.console();
        if (!isTerminal(console)) {
            return Console.unattended(out::println);
        }
        return reading(out, console::readLine);
    }

    /**
     * A console that asks and reads the answer.
     *
     * @param out where questions and narration go
     * @param lines where an answer comes from; {@code null} means end of input
     * @return the console
     */
    static Console reading(PrintStream out, Supplier<String> lines) {
        return new Console() {
            @Override
            public void say(String line) {
                out.println(line);
            }

            @Override
            public Answer ask(String question) {
                out.println();
                out.println(question);
                out.print("  type yes to go on, anything else to stop: ");
                out.flush();
                String typed = lines.get();
                if (typed == null) {
                    // End of input with a terminal attached: the shell that started this has gone,
                    // or somebody pressed ctrl-D. Either way the person the question was for is not
                    // going to answer it, and "no answer" is not "no".
                    out.println();
                    return Answer.UNATTENDED;
                }
                Answer answer = answerTo(typed);
                out.println(answer == Answer.PROCEED ? "  going on." : "  stopping.");
                return answer;
            }
        };
    }

    /**
     * What a typed line means.
     *
     * <p>Only the whole word, and only that word. Anything else stops, including {@code y} and
     * including an empty line, because the default that a stray return key produces has to be the
     * one that leaves the estate where it is.
     *
     * @param typed what was read from the terminal
     * @return proceed, or stop
     */
    static Console.Answer answerTo(String typed) {
        return typed.strip().toLowerCase(Locale.ROOT).equals("yes")
                ? Console.Answer.PROCEED : Console.Answer.STOP;
    }

    /**
     * Whether there is a terminal on the other end.
     *
     * <p>Not a null check on its own, and the reason is a change in the platform that would
     * otherwise make this class wrong on exactly the JDKs this builds for. Up to 21,
     * {@code System.console()} answers null when the streams are redirected, so a null check is the
     * whole question. From 22 it answers a console either way and adds {@code isTerminal()} to tell
     * the two apart — so on 25, which this repository builds and tests on, a null check alone reports
     * a cron job as a person sitting at a keyboard. That is the single worst way for this particular
     * decision to be wrong.
     *
     * <p>Reflection because the source level is 17 and the method does not exist there. One method,
     * named as a constant string in one place, which is what phase 4's native image will have to
     * register.
     *
     * @param console what {@code System.console()} answered
     * @return whether a person could be asked something
     */
    private static boolean isTerminal(java.io.Console console) {
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = java.io.Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (NoSuchMethodException before22) {
            // Before 22 the null check above was the whole question, and it passed.
            return true;
        } catch (ReflectiveOperationException | RuntimeException unexpected) {
            // The question could not be answered, and "I cannot tell whether anybody is there" has
            // to resolve the same way as "nobody is there". The cost of being wrong in this
            // direction is a run that refuses and has to be started again from a terminal; the cost
            // of being wrong in the other is a cutover that answers its own questions.
            return false;
        }
    }
}
