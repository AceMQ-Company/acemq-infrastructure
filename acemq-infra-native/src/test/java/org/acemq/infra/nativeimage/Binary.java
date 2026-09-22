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
package org.acemq.infra.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The thing under test: the executable, started as a subprocess, exactly as a person starts it.
 *
 * <p>Nothing in this module calls {@code Cli} or {@code Executor}. That is the whole point of phase
 * four, and docs/shape.md states it as a rule rather than a preference — reflection is what a native
 * image takes away, a missing registration does not fail the build, and a suite that links against
 * the same classes it is testing cannot see any of it. So everything here goes through a process
 * boundary and an exit code, and the assertions that matter are made against the two brokers
 * afterwards rather than against what the binary said about them.
 *
 * <h2>Why some runs need a terminal, and how one is arranged</h2>
 *
 * <p>Two things a cutover does stop and ask: the gate in front of the first write, and an
 * {@code endpoint: external} switch. {@code Terminal} decides whether anybody is there by asking the
 * runtime, deliberately without any way to answer it from the command line, so a subprocess reached
 * through pipes is correctly told there is nobody — and a blue/green cutover with an external
 * endpoint refuses before it writes anything. That refusal is right, and it also means the most
 * important integration test in the repository cannot be run through a pipe.
 *
 * <p>So it is run through a pseudo-terminal, allocated by {@code script(1)}, which is on every
 * machine this builds on. The binary then finds a terminal because there genuinely is one, the
 * reflective {@code Console.isTerminal} call in {@code Terminal} is exercised for real — it is the
 * one piece of reflection this repository writes itself — and the answer is typed the way the
 * person it was meant for would type it. A test that reached past that by setting a flag would be
 * testing a tool nobody ships.
 *
 * <p>{@code script} is spelled differently on the two platforms and both spellings are here: BSD
 * takes the command as arguments after the typescript file, util-linux takes it as one string after
 * {@code -c} and needs {@code -e} to give back the child's exit status rather than its own.
 */
final class Binary {

    /**
     * Where the binary is, handed over by the build rather than looked for.
     *
     * <p>Failing loudly when it is absent, and never skipping. A run that found no binary and
     * reported green would be reporting on nothing at all, which is the failure
     * {@code etc/check-nothing-skipped.py} exists to make impossible.
     */
    static Path path() {
        String configured = System.getProperty("acemq.infra.binary");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("acemq.infra.binary is not set. This suite runs the"
                    + " native image and there is nothing else for it to run; build it with"
                    + " `mvn -Pnative verify`.");
        }
        Path binary = Path.of(configured);
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException(binary + " is not an executable file. The image did not"
                    + " build, or something ran this suite against a target directory from an"
                    + " earlier build.");
        }
        return binary;
    }

    private Binary() {
    }

    /**
     * Runs the binary with no terminal attached, which is what a pipeline gets.
     *
     * @param arguments the command line
     * @return what it printed and what it exited with
     */
    static Result run(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(path().toString());
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(devNull().toFile()))
                    .start();
            String out = read(process.getInputStream());
            String err = read(process.getErrorStream());
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("the binary did not finish in five minutes: "
                        + String.join(" ", command));
            }
            Result result = new Result(process.exitValue(), out, err);
            System.out.println(result.transcript(command));
            return result;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Runs the binary at a real terminal, so that the questions a cutover asks can be answered.
     *
     * @param arguments the command line
     * @return the running session, which the caller answers and then waits on
     */
    static Session atATerminal(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(path().toString());
        command.addAll(List.of(arguments));
        return new Session(command);
    }

    /** A run that has finished. */
    record Result(int status, String out, String err) {

        /** Everything it printed, wherever it printed it. */
        String all() {
            return out + err;
        }

        /**
         * The same text with every run of whitespace flattened to one space.
         *
         * <p>For asserting on a sentence rather than on a layout. The plan and the run report are
         * wrapped to a readable width and indented under their step, so a phrase that reads as one
         * thing on the screen — "Moving a queue without one of its consumers partitions it" — is
         * three lines and a lot of leading spaces in the string. An assertion written against the
         * wrapping is an assertion that breaks when somebody makes a message clearer, which is a
         * fine way to teach people not to.
         */
        String flat() {
            return all().replaceAll("\\s+", " ");
        }

        String transcript(List<String> command) {
            return "\n$ " + String.join(" ", command) + "\n" + all()
                    + "\n[exit " + status + "]\n";
        }
    }

    /**
     * A run that is still going, with a person at the other end of it.
     *
     * <p>Read character by character rather than line by line, because the question this exists for
     * does not end in a newline: {@code Terminal} prints "type yes to go on, anything else to stop:"
     * and flushes, which is what a prompt is. A reader waiting for a line would wait for the answer
     * it is supposed to be giving.
     */
    static final class Session implements AutoCloseable {

        private final List<String> command;
        private final Process process;
        private final OutputStream keyboard;
        private final StringBuilder screen = new StringBuilder();
        private final Thread reader;

        /** How much of the screen has already been handed to a caller as a question. */
        private int answered;

        private Session(List<String> command) {
            this.command = command;
            try {
                this.process = new ProcessBuilder(terminal(command))
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException broken) {
                throw new UncheckedIOException(broken);
            }
            this.keyboard = process.getOutputStream();
            this.reader = new Thread(this::read, "acemq-infra-transcript");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void read() {
            try (InputStream from = process.getInputStream()) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = from.read(buffer)) != -1) {
                    String text = new String(buffer, 0, count, StandardCharsets.UTF_8);
                    synchronized (screen) {
                        screen.append(text);
                    }
                    // Straight to the build log as it arrives. A cutover that hangs is diagnosed
                    // from the step it hung on, and a transcript printed only after the process
                    // ends is a transcript nobody gets when it does not.
                    System.out.print(text);
                    System.out.flush();
                }
            } catch (IOException ended) {
                // The pseudo-terminal is closed when the child exits, and on both platforms that
                // surfaces as an I/O error rather than as end of stream. Nothing to report.
            }
        }

        /**
         * Waits for the next question and hands back everything printed since the last one.
         *
         * @return the question, and the narration that led up to it
         */
        String awaitQuestion() {
            return awaitQuestion(Duration.ofMinutes(10));
        }

        String awaitQuestion(Duration within) {
            String marker = "type yes to go on";
            Instant deadline = Instant.now().plus(within);
            while (Instant.now().isBefore(deadline)) {
                synchronized (screen) {
                    int at = screen.indexOf(marker, answered);
                    if (at >= 0) {
                        String question = screen.substring(answered, at);
                        answered = at + marker.length();
                        return normalised(question);
                    }
                }
                if (!process.isAlive()) {
                    throw new AssertionError("the binary exited before it asked anything. What it"
                            + " printed:\n" + screenNow());
                }
                pause();
            }
            throw new AssertionError("waited " + within + " for a question and never got one. What"
                    + " it printed:\n" + screenNow());
        }

        /**
         * Types an answer, as a person would.
         *
         * @param typed what to type, without the return key
         */
        void answer(String typed) {
            try {
                keyboard.write((typed + "\n").getBytes(StandardCharsets.UTF_8));
                keyboard.flush();
            } catch (IOException broken) {
                throw new UncheckedIOException(broken);
            }
        }

        /**
         * Waits for the run to end.
         *
         * @param within how long to allow
         * @return what it printed and what it exited with
         */
        Result awaitExit(Duration within) {
            try {
                if (!process.waitFor(within.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("the binary did not finish within " + within
                            + ". What it printed:\n" + screenNow());
                }
                // The reader is on the other side of a pseudo-terminal and the last few hundred
                // bytes can still be in flight when the child exits.
                reader.join(5_000);
                Result result = new Result(process.exitValue(), screenNow(), "");
                System.out.println("\n[exit " + result.status() + "] "
                        + String.join(" ", command) + "\n");
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        private String screenNow() {
            synchronized (screen) {
                return normalised(screen.toString());
            }
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /**
     * The same command line, wrapped in whatever this platform calls a pseudo-terminal.
     *
     * @param command the binary and its arguments
     * @return the command to actually start
     */
    private static List<String> terminal(List<String> command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("bsd")) {
            // BSD: the typescript file first, then the command and its arguments, already split.
            List<String> wrapped = new ArrayList<>(List.of("script", "-q", "/dev/null"));
            wrapped.addAll(command);
            return wrapped;
        }
        // util-linux: one string after -c, and -e so that the exit status handed back is the
        // child's rather than script's own, which is always nought.
        return List.of("script", "-q", "-e", "-c", quoted(command), "/dev/null");
    }

    /** One shell word per argument, so that a path with a space in it stays one argument. */
    private static String quoted(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String word : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append('\'').append(word.replace("'", "'\\''")).append('\'');
        }
        return line.toString();
    }

    /**
     * What a pseudo-terminal writes, read as what it meant.
     *
     * <p>A terminal ends its lines with a carriage return and a newline, and it echoes what was
     * typed at it. Leaving the carriage returns in makes every assertion on the transcript depend on
     * whether it came through a terminal or a pipe, which is precisely the difference these tests
     * are trying not to care about.
     */
    private static String normalised(String raw) {
        return raw.replace("\r\n", "\n").replace("\r", "");
    }

    private static Path devNull() {
        return Path.of("/dev/null");
    }

    private static String read(InputStream stream) throws IOException {
        try (stream) {
            return normalised(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
