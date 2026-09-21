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
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What {@code queues: ["orders.*", "!orders.audit"]} selects, when it is about to be acted on.
 *
 * <p>The same rules the planner applies, deliberately in a second place, and that is worth
 * defending because a duplicated forty lines usually is not. The alternative is for the planner's
 * copy to be made public so the executor can call it, and the thing that would leak across is not
 * the glob: it is that {@code org.acemq.infra.plan} would acquire a caller in the module that
 * writes. The whole enforcement of "plan cannot write" is that nothing in the writing direction is
 * reachable from it, and widening a package's surface to serve the executor is the first small step
 * towards that stopping being true.
 *
 * <p>What holds the two in agreement is that both suites assert the documented examples —
 * {@code ["orders.*", "!orders.audit"]} appears in three pages — so a divergence is a red build
 * rather than a queue nobody drained. Globs, not regular expressions: under a regular expression
 * {@code orders.*} also matches {@code ordersXaudit} and {@code orders}, the dot being a wildcard,
 * and the file's author means the {@code orders} family. A leading {@code !} excludes, and
 * exclusions win.
 */
final class Selection {

    private Selection() {
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

    private static boolean matches(String candidate, List<String> patterns) {
        return included(candidate, patterns) && !excluded(candidate, patterns);
    }

    private static boolean included(String candidate, List<String> patterns) {
        List<String> inclusions = patterns.stream().filter(one -> !one.startsWith("!")).toList();
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
