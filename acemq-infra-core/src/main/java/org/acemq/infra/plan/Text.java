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
import java.util.Locale;

/**
 * The small amount of typesetting a plan needs.
 *
 * <p>Hand-rolled, and the reason is the same one that keeps a databinder out of the parser: a
 * native image is the distribution target and every library brought in for a convenience is a
 * reflective configuration somebody has to maintain. Wrapping a sentence at a margin is forty
 * lines.
 */
final class Text {

    private Text() {
    }

    /**
     * Breaks text at the last space before the margin.
     *
     * <p>The alternative is to let the terminal wrap, and a terminal wraps at the left edge —
     * which puts the second half of a warning in the column the step ids are in, where it reads as
     * a step.
     *
     * @param text the sentence
     * @param width the most characters a line may hold
     * @return the lines, never empty
     */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : collapse(text).split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
        return lines;
    }

    /**
     * One line out of however many the file used.
     *
     * <p>A folded YAML scalar arrives with the newlines the author's editor put in it, and those
     * are a property of the file's column width rather than of the sentence. The plan re-wraps at
     * its own margin, so it has to start from an unwrapped sentence.
     */
    static String collapse(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    /**
     * {@code 1 queue}, {@code 31 queues}, {@code 27,412 messages}.
     *
     * <p>Grouped in the root locale rather than the default one. A plan is a diffable artifact and
     * the machine that produces it is not necessarily the machine that reads it; a thousands
     * separator that changes with a CI runner's locale is a diff that changes for no reason.
     */
    static String count(long many, String singular, String plural) {
        return String.format(Locale.ROOT, "%,d", many) + " " + (many == 1 ? singular : plural);
    }

    /** {@code 31 queues}, where the plural is the singular and an s. */
    static String count(long many, String singular) {
        return count(many, singular, singular + "s");
    }
}
