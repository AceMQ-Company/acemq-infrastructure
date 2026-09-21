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
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What {@code queues: ["orders.*", "!orders.audit"]} selects.
 *
 * <p>Globs, not regular expressions, and the documentation does not say which — so this is a
 * decision rather than a reading, and it is made in the direction of the surprise being smaller.
 * Under a regular expression {@code orders.*} matches {@code ordersXaudit} and {@code orders},
 * because the dot is a wildcard; a file's author writing that line means the {@code orders} family
 * and would be astonished by either. A glob's {@code *} is the only metacharacter anybody expects
 * in a queue name, and every other character matches itself, dots included.
 *
 * <p>A leading {@code !} excludes, and exclusions win. That much docs/configuration.md does show:
 * {@code ["orders.*", "!orders.audit"]} is the whole family except the audit queue, which is the
 * one the file does not want shovelled.
 */
final class Patterns {

    private Patterns() {
    }

    /**
     * The items a pattern list selects.
     *
     * @param items everything there is
     * @param patterns the file's list; an empty one means all of them
     * @param name how to read an item's name
     * @param <T> the kind of thing being selected
     * @return the items selected, in the order they were given
     */
    static <T> List<T> select(List<T> items, List<String> patterns, Function<T, String> name) {
        List<T> selected = new ArrayList<>();
        for (T item : items) {
            if (matches(name.apply(item), patterns)) {
                selected.add(item);
            }
        }
        return selected;
    }

    /**
     * The names an exclusion took back out.
     *
     * <p>Reported separately because the plan says {@code 29 queues (orders.audit excluded)}, and
     * the second half of that is the more interesting one: an operator reading the plan is
     * checking that the queue they deliberately left behind is the queue that was left behind.
     *
     * @param items everything there is
     * @param patterns the file's list
     * @param name how to read an item's name
     * @param <T> the kind of thing being selected
     * @return the names that an inclusion matched and an exclusion then removed
     */
    static <T> List<String> excludedNames(List<T> items, List<String> patterns,
                                          Function<T, String> name) {
        List<String> excluded = new ArrayList<>();
        for (T item : items) {
            String candidate = name.apply(item);
            if (included(candidate, patterns) && !matches(candidate, patterns)) {
                excluded.add(candidate);
            }
        }
        return excluded;
    }

    private static boolean matches(String candidate, List<String> patterns) {
        return included(candidate, patterns) && !excluded(candidate, patterns);
    }

    private static boolean included(String candidate, List<String> patterns) {
        List<String> inclusions = patterns.stream().filter(one -> !one.startsWith("!")).toList();
        // No inclusions at all -- an empty list, or a list that is nothing but exclusions -- means
        // everything. "!orders.audit" on its own is a readable way to say "all of it but that".
        return inclusions.isEmpty()
                || inclusions.stream().anyMatch(pattern -> glob(pattern).matcher(candidate).matches());
    }

    private static boolean excluded(String candidate, List<String> patterns) {
        return patterns.stream().filter(one -> one.startsWith("!"))
                .anyMatch(pattern -> glob(pattern.substring(1)).matcher(candidate).matches());
    }

    private static Pattern glob(String pattern) {
        StringBuilder expression = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char character : pattern.toCharArray()) {
            if (character == '*' || character == '?') {
                if (literal.length() > 0) {
                    expression.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                expression.append(character == '*' ? ".*" : ".");
            } else {
                literal.append(character);
            }
        }
        if (literal.length() > 0) {
            expression.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(expression.toString());
    }
}
