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

import java.util.ArrayList;
import java.util.List;

import org.acemq.infra.config.Step;

/**
 * What happened, step by step, and what the estate is now.
 *
 * <p>A value rather than a log, for the same reason {@link org.acemq.infra.plan.Plan} is: a test
 * asks which step was sixth and whether it wrote anything, without reading a paragraph, and the
 * rendering is one string at the edge.
 *
 * <p>The field that earns its keep when things go wrong is {@link #movements()}. A run that aborts
 * partway has usually declared something that is still running on a broker — a shovel with a
 * backlog left in it — and the single most useful thing a report can carry at that moment is the
 * name of it on the cluster it is on. The executor tears down what it declared before it gives up,
 * so this is normally empty; it is not empty when the teardown itself failed, which is precisely
 * the case where somebody has to go and look.
 *
 * @param name the deployment's name
 * @param mode whether this run was allowed to write
 * @param outcome how it ended
 * @param steps what each step did
 * @param notes things that are true and that somebody is going to be surprised by
 * @param movements anything this run declared that is still declared, which should be nothing
 * @param rollback the steps that would undo what actually happened — derived, not transcribed
 */
public record Execution(String name, Run.Mode mode, Outcome outcome, List<Taken> steps,
                        List<String> notes, List<Broker.Movement> movements,
                        List<Step> rollback) {

    public Execution {
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
        movements = List.copyOf(movements);
        rollback = List.copyOf(rollback);
    }

    /** How a run ended. */
    public enum Outcome {

        /** Every step was carried out. */
        COMPLETED,

        /** Nothing was attempted: the preflight found something wrong before the first write. */
        REFUSED,

        /** A step failed or a guard expired with {@code onTimeout: abort}. */
        ABORTED,

        /** A human said stop, or there was no human to ask and the step needed one. */
        STOPPED
    }

    /** What one step did. */
    public enum Status {

        /** It was carried out. */
        DONE,

        /** This was a rehearsal, and the step reported what it would have done. */
        REHEARSED,

        /** It is what stopped the run. */
        FAILED,

        /** The run had already stopped by the time it came round. */
        NOT_REACHED
    }

    /**
     * One step, and what came of it.
     *
     * @param number its position, counting from one and counting the backup
     * @param id the step's id, from the file or from the default list
     * @param status what came of it
     * @param lines what it did, or would have done, one line each
     */
    public record Taken(int number, String id, Status status, List<String> lines) {

        public Taken {
            lines = List.copyOf(lines);
        }
    }

    /** Whether the estate is where the plan said it would be. */
    public boolean ok() {
        return outcome == Outcome.COMPLETED;
    }

    /** The steps that were actually carried out, which is what a rollback is derived from. */
    public List<Taken> done() {
        return steps.stream().filter(step -> step.status() == Status.DONE).toList();
    }

    /**
     * The report, as it goes on a screen and into an incident channel.
     *
     * @return the whole thing, newline-separated, with a trailing newline
     */
    public String render() {
        List<String> out = new ArrayList<>();
        out.add(name + " — " + (mode == Run.Mode.REHEARSE ? "rehearsal" : "cutover") + " — "
                + outcome.name().toLowerCase(java.util.Locale.ROOT));
        out.add("");
        for (Taken step : steps) {
            boolean first = true;
            for (String line : step.lines().isEmpty() ? List.of("") : step.lines()) {
                out.add(pad(first ? "  " + step.number() + " " + step.id() : "")
                        + (first ? mark(step.status()) + " " : " ".repeat(MARK + 1)) + line);
                first = false;
            }
        }
        if (!movements.isEmpty()) {
            out.add("");
            out.add("STILL DECLARED — these were left behind and are moving messages now");
            movements.forEach(movement -> bullet(out, movement.describe()));
        }
        if (!notes.isEmpty()) {
            out.add("");
            out.add("notes");
            notes.forEach(note -> bullet(out, note));
        }
        out.add("");
        out.add(closing());
        return String.join("\n", out) + "\n";
    }

    private String closing() {
        if (mode == Run.Mode.REHEARSE) {
            return "nothing was written: this was a rehearsal.";
        }
        return switch (outcome) {
            case COMPLETED -> "the cutover completed. " + rollbackLine();
            case REFUSED -> "nothing was written: the run was refused before the first step.";
            // Both of these leave a half-moved estate, and the sentence that matters is the one
            // about getting out of it rather than the one about what went wrong.
            case ABORTED -> "the cutover stopped partway. " + rollbackLine();
            case STOPPED -> "the cutover was stopped. " + rollbackLine();
        };
    }

    private String rollbackLine() {
        return rollback.isEmpty()
                ? "nothing that happened needs undoing."
                : "the rollback for what happened is " + rollback.size() + " steps.";
    }

    /** Where the left column ends, matching the plan's so the two can be read side by side. */
    private static final int GUTTER = 20;

    /** How wide the status mark is. Every one of them is padded to it so the text lines up. */
    private static final int MARK = 6;

    /** Where a note wraps. Eighty columns, minus the margin a terminal takes back. */
    private static final int WIDTH = 78;

    private static String mark(Status status) {
        return switch (status) {
            case DONE -> "done  ";
            case REHEARSED -> "would ";
            case FAILED -> "FAILED";
            case NOT_REACHED -> "-     ";
        };
    }

    /**
     * One note, wrapped.
     *
     * <p>Wrapped here rather than left to the terminal, which wraps at the left edge — and the left
     * edge is the column the step ids are in, so the second half of a note reads as a step.
     */
    private static void bullet(List<String> out, String text) {
        String remaining = text.strip().replaceAll("\\s+", " ");
        String prefix = "  · ";
        while (!remaining.isEmpty()) {
            int room = WIDTH - 4;
            if (remaining.length() <= room) {
                out.add(prefix + remaining);
                return;
            }
            int cut = remaining.lastIndexOf(' ', room);
            if (cut <= 0) {
                cut = room;
            }
            out.add(prefix + remaining.substring(0, cut));
            remaining = remaining.substring(cut).stripLeading();
            prefix = "    ";
        }
    }

    private static String pad(String left) {
        return left.length() >= GUTTER - 1 ? left + " "
                : left + " ".repeat(GUTTER - left.length());
    }
}
