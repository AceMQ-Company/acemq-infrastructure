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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The command an estate supplied for moving its own endpoint.
 *
 * <p>docs/blue-green.md is firm that this step is not this tool's: the management API can close a
 * connection and cannot decide where the client reconnects to, because that is DNS, or a load
 * balancer, or a connection string in a config map, and every estate does it differently. So a hook
 * is a command somebody else wrote, and the executor's whole contribution is to run it, wait for it
 * and believe its exit code.
 *
 * <p>Believe it, and nothing else. A hook that exits non-zero has not switched the endpoint, and
 * carrying on from there produces the failure docs/message-state.md names: clients come back to the
 * cluster that was just emptied, and the cutover looks exactly like one that worked until the next
 * incident. So a non-zero exit aborts, and the output is kept — a hook's stderr is usually the only
 * thing that says what went wrong with somebody else's DNS.
 *
 * <p>Started with no shell. The command and its arguments go to the process as a list, so a
 * {@code {{target}}} substituted into an argument is an argument rather than something a shell
 * re-reads for metacharacters.
 */
final class Hooks {

    /** How much of a talkative hook's output is worth keeping in a report. */
    private static final int LINES = 20;

    private Hooks() {
    }

    /**
     * Runs a hook and waits for it.
     *
     * @param command the command, as {@code endpoint.run} wrote it
     * @param arguments its arguments, with {@code {{target}}} already substituted
     * @param timeout how long it may take
     * @param lines where to record what it said
     * @return whether it exited zero within the timeout
     */
    static boolean run(String command, List<String> arguments, Duration timeout,
                       List<String> lines) {
        List<String> line = new ArrayList<>();
        line.add(command);
        line.addAll(arguments);

        Process process;
        try {
            process = new ProcessBuilder(line).redirectErrorStream(true).start();
        } catch (IOException notStarted) {
            lines.add("the hook could not be started: " + notStarted.getMessage());
            return false;
        }

        List<String> said = new ArrayList<>();
        try (var reader = process.inputReader()) {
            String read;
            while ((read = reader.readLine()) != null) {
                said.add(read);
            }
        } catch (IOException unreadable) {
            said.add("(its output could not be read: " + unreadable.getMessage() + ")");
        }

        boolean exited;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            lines.add("the hook was interrupted");
            return false;
        }

        said.stream().limit(LINES).forEach(one -> lines.add("| " + one));
        if (said.size() > LINES) {
            lines.add("| (" + (said.size() - LINES) + " more lines)");
        }

        if (!exited) {
            // Killed rather than left running. A hook that has not finished has not switched the
            // endpoint, and one still going while the run aborts is a second thing changing the
            // estate with nobody watching it.
            process.destroy();
            lines.add("the hook did not finish within " + timeout + " and was stopped");
            return false;
        }
        int code = process.exitValue();
        lines.add("the hook exited " + code);
        return code == 0;
    }
}
