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

import java.util.function.Consumer;

/**
 * The human, if there is one.
 *
 * <p>Two steps in a cutover stop and ask: a guard whose {@code onTimeout} is {@code prompt}, and an
 * {@code endpoint: external} switch, which is the one step docs/blue-green.md says this tool does
 * not own. Both need an answer from somebody watching, and the thing that must not happen is for
 * either to be answered by the tool on that person's behalf.
 *
 * <p>Hence {@link Answer#UNATTENDED}, which is a different answer from "no". A run with nobody
 * watching has not declined; there was nothing there to decline. The executor treats it as a stop,
 * and says in the report that it stopped because the question could not be asked — because "the
 * operator chose to abort" and "there was no operator" are two very different lines to read the
 * morning after, and only one of them tells you to run it again from a terminal.
 *
 * <p>docs/blue-green.md puts it the other way round and means the same thing: the value of
 * {@code onTimeout} being in the file is that the decision is made at review time by people with
 * the whole picture, rather than at three in the morning by whoever is watching the timer. A
 * {@code prompt} in an unattended pipeline is that decision never being made at all.
 */
public interface Console {

    /**
     * Says something as the run goes along.
     *
     * @param line one line, already complete
     */
    void say(String line);

    /**
     * Stops and asks.
     *
     * @param question what to ask, written so that the answer is obvious to somebody who has just
     *     walked up to the terminal
     * @return what they said, or {@link Answer#UNATTENDED} when there was nobody to ask
     */
    Answer ask(String question);

    /** What somebody said. */
    enum Answer {

        /** Carry on. */
        PROCEED,

        /** Stop here. */
        STOP,

        /** There was nobody to ask, which is not the same as being told to stop. */
        UNATTENDED
    }

    /**
     * A console that talks and cannot listen: a pipeline, a scheduled job, a test.
     *
     * @param sink where the lines go
     * @return a console whose every question comes back {@link Answer#UNATTENDED}
     */
    static Console unattended(Consumer<String> sink) {
        return new Console() {
            @Override
            public void say(String line) {
                sink.accept(line);
            }

            @Override
            public Answer ask(String question) {
                sink.accept(question);
                return Answer.UNATTENDED;
            }
        };
    }
}
