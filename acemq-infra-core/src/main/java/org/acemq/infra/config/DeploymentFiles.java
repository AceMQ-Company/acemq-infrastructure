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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading a deployment file off disk.
 *
 * <p>The one place a path becomes a document. Split from {@link DeploymentFileParser} so that
 * everything above it — the validator, the planner, every test — works on text with a name rather
 * than on a filesystem.
 */
public final class DeploymentFiles {

    private DeploymentFiles() {
    }

    /**
     * @param file the deployment file
     * @param environment where {@code ${VAR}} references are looked up
     * @return the parsed file
     * @throws ConfigException if it cannot be represented; see that class for where the line falls
     * @throws UncheckedIOException if it cannot be read
     */
    public static DeploymentFile load(Path file, Environment environment) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + file, error);
        }
        // The path as the caller wrote it, not the absolute one. Every message in the report is
        // going to repeat it, and an operator who typed `orders.yaml` should not get forty
        // characters of their own home directory back on each line.
        return DeploymentFileParser.parse(text, file.toString(), environment);
    }
}
