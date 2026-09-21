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
package org.acemq.infra.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.acemq.infra.yaml.Location;

/**
 * {@code ${VAR}} substitution, done on the text before it is parsed.
 *
 * <p>The one change from the format this inherits, and the reason this class is not four lines:
 * <strong>an unset variable is an error</strong>. The old C# implementation left the literal
 * {@code ${BLUE_PASSWORD}} in place, which meant the file parsed, the plan printed, the cutover
 * started, and the failure arrived as an authentication error against blue somewhere around step
 * four with the topology already copied. Failing here costs a second and names the variable.
 *
 * <p>Every unset variable in the file is reported at once. A file is usually run in an environment
 * that is missing all of them or none of them, so reporting one at a time would mean one run per
 * secret.
 *
 * <p>Substitution happens on the text rather than on parsed values, which is how the format has
 * always worked and is what lets a variable supply a whole structure if somebody wants one. It has
 * one consequence worth knowing: a value containing a newline shifts every line number below it,
 * so the positions in later messages would be off by however many lines it added. Credentials and
 * URLs do not contain newlines, so this has not been worth guarding against, but it is the reason
 * to look twice if a location ever seems wrong.
 */
public final class Interpolation {

    /** The same expression scripts/lint-deployment.py uses, so the two agree on what a reference is. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private Interpolation() {
    }

    /**
     * @param text the document as written
     * @param file where it came from, for the messages
     * @param environment where to look the variables up
     * @return the document with every reference replaced
     * @throws ConfigException if any referenced variable is unset, naming all of them
     */
    public static String apply(String text, String file, Environment environment) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        List<ConfigException.Problem> problems = new ArrayList<>();
        Set<String> alreadyReported = new LinkedHashSet<>();

        while (matcher.find()) {
            String name = matcher.group(1);
            Optional<String> value = environment.lookup(name);
            if (value.isPresent()) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value.get()));
                continue;
            }
            // Left in place so that the rest of the substitution can carry on and find the other
            // unset variables. Nothing downstream will see this text: the exception is thrown
            // before the result is returned.
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            // A variable used in four places is one thing to fix, so it is reported once, at the
            // first place it appears.
            if (alreadyReported.add(name)) {
                problems.add(new ConfigException.Problem(
                        new Location(file, lineOf(text, matcher.start()), 0),
                        "${" + name + "}",
                        "is not set in this environment. An unset variable is an error rather "
                                + "than an empty string: the alternative is a plan that reads "
                                + "correctly and a cutover that fails authenticating halfway "
                                + "through"));
            }
        }
        matcher.appendTail(result);

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return result.toString();
    }

    /** Every variable the text refers to, in the order it first refers to them. */
    public static Set<String> references(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }
}
