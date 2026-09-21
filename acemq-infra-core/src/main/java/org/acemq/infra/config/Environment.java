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

import java.util.Map;
import java.util.Optional;

/**
 * Where {@code ${VAR}} references are looked up.
 *
 * <p>An interface rather than a direct call to {@code System.getenv} so that the substitution can
 * be tested without setting process environment variables, which is a thing a test cannot undo
 * for the tests that run after it.
 */
@FunctionalInterface
public interface Environment {

    /** The value of {@code name}, or empty if it is not set. */
    Optional<String> lookup(String name);

    /** The process environment. */
    static Environment system() {
        return name -> Optional.ofNullable(System.getenv(name));
    }

    /** A fixed set of variables, for tests and for callers that assemble their own context. */
    static Environment of(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name));
    }
}
