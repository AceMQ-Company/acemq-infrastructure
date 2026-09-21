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
package org.acemq.infra.yaml;

import java.util.Objects;

/**
 * Where in which file. Carried by every node and every parsed record.
 *
 * <p>This exists because of how a deployment file is read. It lands in a pull request days
 * before it runs and the person reviewing it did not write it, so an error that says
 * {@code deployment.steps[5]: something} makes that reader count steps by hand. One that says
 * {@code orders.yaml:107} puts their cursor on the line.
 *
 * @param file where the document came from — a path, or a name for a document held in memory
 * @param line 1-based, or 0 when the position is not known
 * @param column 1-based, or 0 when the position is not known
 */
public record Location(String file, int line, int column) {

    public Location {
        Objects.requireNonNull(file, "file");
    }

    /** A position inside {@code file} that could not be pinned to a line. */
    public static Location unknown(String file) {
        return new Location(file, 0, 0);
    }

    /** Whether a line is actually known, as opposed to {@link #unknown(String)}. */
    public boolean known() {
        return line > 0;
    }

    /**
     * {@code file:line} for a message, or just {@code file} when the line is unknown.
     *
     * <p>The column is deliberately left out. It is accurate for a scalar and misleading for a
     * block mapping, where the position that matters to a reader is the start of the key rather
     * than wherever the value happened to begin.
     */
    public String describe() {
        return known() ? file + ":" + line : file;
    }

    @Override
    public String toString() {
        return describe();
    }
}
