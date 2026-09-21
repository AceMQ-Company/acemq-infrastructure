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
package org.acemq.infra.validate;

import org.acemq.infra.yaml.Location;

/**
 * One thing the validator has to say about a deployment file.
 *
 * <p>Two severities and no third. An error means the file will not be planned; a warning means it
 * will be planned and somebody should have read this first. There is deliberately no "info" or
 * "hint" level: a validator whose output is mostly noise is a validator people stop reading, and
 * the warnings here are all things that have cost somebody messages.
 *
 * @param severity whether this stops the file being planned
 * @param where the field, in the dotted form the deployment file's own messages use — for a step,
 *     {@code deployment.steps[5](drain-messages)}, because a reader counting list entries by hand
 *     gets it wrong and the id is what the plan output will call it anyway
 * @param message what is wrong and, where there is one, what to do instead
 * @param location the file and, where it is known, the line
 */
public record Finding(Severity severity, String where, String message, Location location) {

    /** Whether the file will be planned. */
    public enum Severity {
        /** The file will not be planned. */
        ERROR,
        /** The file will be planned, and this is worth reading before it is. */
        WARNING
    }

    static Finding error(String where, String message, Location location) {
        return new Finding(Severity.ERROR, where, message, location);
    }

    static Finding warning(String where, String message, Location location) {
        return new Finding(Severity.WARNING, where, message, location);
    }

    /** Whether this is an error. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return location.describe() + ": " + where + ": " + message;
    }
}
