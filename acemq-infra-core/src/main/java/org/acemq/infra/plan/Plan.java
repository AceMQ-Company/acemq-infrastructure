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
import java.util.List;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.provider.ProbedCluster;

/**
 * What a cutover would do, in order, and what it would not.
 *
 * <p>A value, and text only at the edge. The plan is assembled as a record so that a test can ask
 * which step is sixth without reading a paragraph, and rendered by {@link #render()} so that the
 * thing a pull request reviews is one string that a diff can show.
 *
 * <p><strong>This record is the whole of what {@code plan} produces.</strong> There is no other
 * output and no side channel: the planner is handed two {@link ProbedCluster} values and a parsed
 * file, and it returns this. It holds no connection, so there is nothing for it to write through
 * even if some future change wanted to.
 *
 * @param name the deployment's name, from {@code metadata.name}
 * @param headline the operation and clusters, as the first line states them
 * @param source what the cluster being moved away from turned out to be
 * @param target what the cluster being moved to turned out to be
 * @param required the capabilities the file's {@code requires:} list asserted, and whether the
 *     clusters turned out to have them
 * @param steps the numbered steps
 * @param warnings the things that are true and are about to surprise somebody
 * @param refusals the reasons this cutover cannot run as written; empty means it can
 */
public record Plan(String name, String headline, ProbedCluster source, ProbedCluster target,
                   List<Requirement> required, List<PlannedStep> steps, List<String> warnings,
                   List<String> refusals) {

    /**
     * One entry of a {@code probe} step's {@code requires:} list, answered.
     *
     * @param capability what the file asserted
     * @param met whether both clusters turned out to have it
     */
    public record Requirement(Capability capability, boolean met) {
    }

    /** Where the left column ends and a step's description begins. */
    private static final int GUTTER = 20;

    /** Where a warning wraps. Eighty columns, minus the margin a terminal takes back. */
    private static final int WIDTH = 78;

    public Plan {
        required = List.copyOf(required);
        steps = List.copyOf(steps);
        warnings = List.copyOf(warnings);
        refusals = List.copyOf(refusals);
    }

    /** Whether the cutover could run as written. Warnings do not make it false; refusals do. */
    public boolean ok() {
        return refusals.isEmpty();
    }

    /**
     * The plan as it goes into a pull request.
     *
     * <p>Deterministic, to the character. Nothing here reads a clock, a working directory or an
     * environment variable — a {@code {{timestamp}}} in a backup path stays a
     * {@code {{timestamp}}} — because a plan that changes every time it is produced cannot be
     * diffed, and diffing two plans is how a reviewer sees that a file's edit did what its author
     * said it did.
     *
     * @return the whole plan, newline-separated, with a trailing newline
     */
    public String render() {
        List<String> out = new ArrayList<>();
        out.add(name + " — " + headline);
        out.add("");
        renderProbe(out);
        out.add("");
        for (PlannedStep step : steps) {
            boolean first = true;
            for (String line : step.lines()) {
                out.add(pad(first ? "  " + step.number() + " " + step.id() : "") + line);
                first = false;
            }
        }
        if (!warnings.isEmpty()) {
            out.add("");
            out.add("warnings");
            warnings.forEach(warning -> bullet(out, warning));
        }
        if (!refusals.isEmpty()) {
            out.add("");
            out.add("refused");
            refusals.forEach(refusal -> bullet(out, refusal));
        }
        out.add("");
        out.add(closing());
        return String.join("\n", out) + "\n";
    }

    private void renderProbe(List<String> out) {
        // The two release strings are padded against each other so that the facility marks form a
        // column. Two clusters at different versions is the normal case -- that is usually the
        // point of the cutover -- and a ragged column is where a missing plugin hides.
        int width = Math.max(source.name().length(), target.name().length());
        int release = Math.max(source.release().length(), target.release().length());
        out.add(pad("  probe") + describe(source, width, release));
        out.add(pad("") + describe(target, width, release));
        if (!required.isEmpty()) {
            List<String> missing = required.stream().filter(one -> !one.met())
                    .map(one -> one.capability().name()).toList();
            out.add(pad("") + (missing.isEmpty()
                    ? "all " + required.size() + " required capabilities present"
                    : required.size() + " required capabilities, " + missing.size()
                            + " missing: " + String.join(", ", missing)));
        }
        for (String note : source.notes()) {
            out.add(pad("") + source.name() + ": " + note);
        }
        for (String note : target.notes()) {
            out.add(pad("") + target.name() + ": " + note);
        }
    }

    private static String describe(ProbedCluster cluster, int name, int release) {
        return fill(cluster.name(), name) + " " + fill(cluster.release(), release) + "  "
                + cluster.facilityMarks();
    }

    /** The last line, which is the one the whole milestone is about. */
    private String closing() {
        if (!ok()) {
            return "nothing was written, and nothing would be: this plan is refused.";
        }
        // docs/roadmap.md ends this line with `run acemq-infra apply` to execute, and now there is
        // one. The file is not named because this record does not know what it was called and a
        // path invented here would be a path somebody pastes.
        return "nothing was written. run `acemq-infra apply -f` on this file to execute it, or"
                + " `apply --dry-run` to see what each step would do right now.";
    }

    private static void bullet(List<String> out, String text) {
        List<String> wrapped = Text.wrap(text, WIDTH - 4);
        for (int index = 0; index < wrapped.size(); index++) {
            out.add((index == 0 ? "  · " : "    ") + wrapped.get(index));
        }
    }

    private static String pad(String left) {
        return fill(left, GUTTER - 1) + " ";
    }

    private static String fill(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
