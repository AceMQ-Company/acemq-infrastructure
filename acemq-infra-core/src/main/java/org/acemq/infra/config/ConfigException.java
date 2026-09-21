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

import java.util.List;
import java.util.stream.Collectors;

import org.acemq.infra.yaml.Location;

/**
 * The file could not be turned into a deployment model at all.
 *
 * <p>This is the line between the two halves of reading a deployment file, and it is worth being
 * precise about where it falls, because the other half — {@code org.acemq.infra.validate} — has
 * most of the interesting rules in it.
 *
 * <p>A {@code ConfigException} means the document could not be <em>represented</em>: the YAML is
 * malformed, a field that must be a mapping is a list, a closed vocabulary was given a word that
 * is not in it, a duration is not a duration, an interpolated variable is not set. There is no
 * model on the far side of any of those, so there is nothing for a rule to run against.
 *
 * <p>Everything else — a required field that is absent, a cluster name that refers to nothing, a
 * drain in the wrong place — produces a model perfectly well and is a finding from the validator.
 * Absence in particular is a validation matter, not a parse one: {@code semantics:} missing is the
 * single most consequential thing a deployment file can omit, and it deserves the validator's
 * sentence about what the two values mean rather than a parser's sentence about a missing key.
 *
 * <p>Problems are collected and thrown together. A file with four unset variables should say so
 * once, not four times over four runs.
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Problem> problems;

    /**
     * One thing that stopped the file being read.
     *
     * @param location the file and, where it is known, the line
     * @param path where in the document, in the dotted form the deployment file's own messages use
     * @param message what was wrong and what was expected instead
     */
    public record Problem(Location location, String path, String message) {

        @Override
        public String toString() {
            return location.describe() + ": " + path + ": " + message;
        }
    }

    /**
     * @param problems everything found, in the order the file writes it; must not be empty
     */
    public ConfigException(List<Problem> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    /** Everything that was wrong, not only the first thing. */
    public List<Problem> problems() {
        return problems;
    }

    private static String render(List<Problem> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("a ConfigException with no problems says nothing");
        }
        if (problems.size() == 1) {
            return problems.get(0).toString();
        }
        return problems.size() + " problems:" + System.lineSeparator()
                + problems.stream().map(problem -> "  " + problem)
                        .collect(Collectors.joining(System.lineSeparator()));
    }
}
