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
package org.acemq.infra.cli;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.acemq.infra.config.Environment;

/**
 * An environment that answers for a variable it does not have, and remembers that it did.
 *
 * <h2>The decision this class is</h2>
 *
 * <p>docs/library.md leaves one question open for whoever writes the CLI, and it is this one:
 * {@code ${VAR}} is fatal when unset, {@code scripts/lint-deployment.py} treats the same condition
 * as a warning, and something has to decide what {@code validate} does in the continuous
 * integration where a deployment file is reviewed — an environment that deliberately holds no
 * production secrets, and should not be made to hold them.
 *
 * <p><strong>The answer taken here: the two commands differ, because the two commands do different
 * things next.</strong> {@code plan} authenticates against two brokers, so an unset variable is
 * fatal for it and stays fatal. {@code validate} never opens a socket. Its whole job is to answer
 * "is this file well-formed, and do its rules hold" — and that question has an answer whether or
 * not the password is in the room. So {@code validate} substitutes the reference back in as its
 * own text, reports every name it had to do that for, and carries on.
 *
 * <p>The alternative is defensible and was rejected for a specific reason. Making {@code validate}
 * fatal is one behaviour rather than two, which is worth something — but the consequence is that a
 * pull-request check cannot run it, so every pipeline invents a set of fake environment variables
 * to get past a check about structure, and the fake values then have to be maintained as the file
 * grows. That is a worse trap than two behaviours: it is a check that appears to be validating a
 * file against the environment it will run in and is validating it against a fixture. And the
 * practical effect of refusing is that teams keep running the Python linter, which does not refuse
 * — which would leave two implementations and no reason for either to agree with the other, when
 * the entire point of moving the rules into Java was to end up with one.
 *
 * <p>So the difference is not a relaxation. It is the same question with a different next step,
 * and the flag says so: {@code --require-variables} makes {@code validate} behave exactly as
 * {@code plan} does, for the pipeline that does hold the secrets and wants to prove a file
 * resolves before a cutover window rather than inside one.
 *
 * <p>What it substitutes is the reference itself — {@code ${BLUE_PASSWORD}} stays
 * {@code ${BLUE_PASSWORD}} — rather than an empty string, because the rules being checked are
 * about presence and shape and an empty string fails half of them for the wrong reason. That is
 * the behaviour docs/configuration.md calls out as the old format's bug, and it was a bug there
 * because the value was then <em>used</em>. Here nothing is ever dialled: the command that would
 * dial it refuses to run without the real thing.
 */
final class Variables implements Environment {

    private final Environment delegate;
    private final Set<String> unset = new LinkedHashSet<>();

    private Variables(Environment delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps an environment so that a missing variable is answered rather than fatal.
     *
     * @param delegate where a variable is really looked up
     * @return the recording environment
     */
    static Variables recording(Environment delegate) {
        return new Variables(delegate);
    }

    @Override
    public Optional<String> lookup(String name) {
        Optional<String> value = delegate.lookup(name);
        if (value.isPresent()) {
            return value;
        }
        unset.add(name);
        return Optional.of("${" + name + "}");
    }

    /** The variables that were not set, in the order the file first referred to them. */
    Set<String> unset() {
        return unset;
    }
}
