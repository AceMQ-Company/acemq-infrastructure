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

import org.acemq.infra.yaml.Location;

/**
 * A key the model has no field for, kept rather than discarded.
 *
 * <p>The parser could reject these, and deliberately does not, because the validator has more to
 * say about some of them than "unknown key" — and because the rule it has most to say about is the
 * one that matters most. {@code percentage:} in a canary is not a typo; it is somebody writing the
 * canary that every other kind of deployment has, and the answer is four sentences about what a
 * percentage does to a queue rather than a shrug about an unrecognised field (docs/canary.md).
 *
 * <p>So unknown keys are collected with where they were written, and the validator decides what
 * each one means.
 *
 * @param path where the containing mapping is, in dotted form — {@code deployment.scope}, or the
 *     empty string for the top level
 * @param name the key itself
 * @param location the file and line the key is written on
 */
public record UnknownKey(String path, String name, Location location) {

    /** {@code deployment.scope.percentage}, or just {@code percentage} at the top level. */
    public String qualified() {
        return path.isEmpty() ? name : path + "." + name;
    }
}
