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

/**
 * The file is not YAML, or is YAML this reader refuses.
 *
 * <p>Thrown before anything knows the document is a deployment file, which is why it lives here
 * and not beside the configuration model: at this point the only thing that has been established
 * is that a stream of bytes did or did not compose into a tree.
 */
public class YamlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Location location;

    /**
     * @param location where the reader stopped, or {@link Location#unknown(String)}
     * @param message what was wrong, phrased so that it names what was expected
     */
    public YamlException(Location location, String message) {
        super(location.describe() + ": " + message);
        this.location = location;
    }

    /** Where the reader stopped. */
    public Location location() {
        return location;
    }
}
