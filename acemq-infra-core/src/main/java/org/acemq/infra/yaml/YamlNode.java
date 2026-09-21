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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A YAML document as a tree that remembers where each piece came from.
 *
 * <p>The alternative was binding the file straight into the records with a databinder, and it was
 * rejected for two reasons that both bite later rather than now. A databinder loses the line
 * numbers — it hands back an object graph and the position information is gone by then, so every
 * error the parser and the validator produce would name a field and nothing else. And a
 * databinder binds by reflection, which is the one thing a GraalVM native image takes away; every
 * record would need a registration and a missing one fails in the field rather than in the build
 * (docs/shape.md is explicit about this being the price of choosing Java).
 *
 * <p>So the file is composed into this tree, marks and all, and mapped into records by hand.
 *
 * <p>An explicit YAML null — {@code caFile:} with nothing after it — is {@link Null} rather than a
 * {@code Scalar} holding {@code null}, so that reading a value never has to ask which kind of
 * nothing it found.
 */
public sealed interface YamlNode {

    /** Where this node begins. */
    Location location();

    /**
     * What this node is, in the words an error message uses: {@code a mapping}, {@code a
     * sequence}, {@code a value}, {@code nothing}.
     */
    String describe();

    /** A block or flow mapping, with its keys in the order the file wrote them. */
    record Mapping(Map<String, YamlNode> entries, Map<String, Location> keyLocations,
                   Location location) implements YamlNode {

        /** An empty mapping, used where a malformed one has already been reported. */
        public static Mapping empty(Location location) {
            return new Mapping(Map.of(), Map.of(), location);
        }

        /** The value at {@code key}, or empty when the key is absent. */
        public Optional<YamlNode> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        /** Whether the key is written in the file at all, whatever its value. */
        public boolean has(String key) {
            return entries.containsKey(key);
        }

        /** The keys, in file order. */
        public Set<String> keys() {
            return entries.keySet();
        }

        /**
         * Where {@code key} itself is written, which is the position an error about that field
         * should point at — not the value's, which for a block mapping is the line below.
         */
        public Location keyLocation(String key) {
            return keyLocations.getOrDefault(key, location);
        }

        @Override
        public String describe() {
            return "a mapping";
        }
    }

    /** A block or flow sequence. */
    record Sequence(List<YamlNode> items, Location location) implements YamlNode {

        @Override
        public String describe() {
            return "a sequence";
        }
    }

    /**
     * A scalar, kept as the text the file wrote plus the tag SnakeYAML resolved.
     *
     * <p>The tag is what separates {@code depth: 0} from {@code depth: "0"} and
     * {@code verify: true} from {@code verify: "true"}. Keeping both means a field that wants a
     * number can say so, and a field that wants a string is not handed a boolean because somebody
     * wrote a username of {@code no}.
     *
     * @param text the scalar as written, unquoted
     * @param tag the resolved YAML tag, such as {@code tag:yaml.org,2002:int}
     */
    record Scalar(String text, String tag, Location location) implements YamlNode {

        /** Whether the tag resolved to an integer. */
        public boolean isInteger() {
            return "tag:yaml.org,2002:int".equals(tag);
        }

        /** Whether the tag resolved to a boolean. */
        public boolean isBoolean() {
            return "tag:yaml.org,2002:bool".equals(tag);
        }

        @Override
        public String describe() {
            return "a value";
        }
    }

    /** An explicit null: a key written with nothing after it, or {@code ~}. */
    record Null(Location location) implements YamlNode {

        @Override
        public String describe() {
            return "nothing";
        }
    }
}
