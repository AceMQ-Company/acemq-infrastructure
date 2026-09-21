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

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Reads YAML text into a {@link YamlNode} tree.
 *
 * <p>Composes rather than loads. {@code Yaml.load} hands back maps and lists with every mark
 * discarded, and the marks are the whole reason this layer exists — see {@link YamlNode}.
 *
 * <p>Three things are refused here rather than later, because none of them can be represented in
 * the tree and all three are silent when they are tolerated:
 *
 * <ul>
 *   <li>a non-scalar mapping key, which YAML permits and no deployment file has ever wanted;
 *   <li>a duplicate key, where the last one quietly wins — a file with {@code semantics:} written
 *       twice would run under whichever copy came second, and that is exactly the field
 *       docs/configuration.md refuses to let the tool decide;
 *   <li>a second document in the stream, because a deployment file describes one deployment and
 *       the extra one would be read by nothing and noticed by nobody.
 * </ul>
 */
public final class YamlReader {

    private YamlReader() {
    }

    /**
     * @param text the document
     * @param file where it came from, for the messages
     * @return the root node, which is whatever the file's single document is
     * @throws YamlException if the text is not one well-formed YAML document
     */
    public static YamlNode read(String text, String file) {
        LoaderOptions options = new LoaderOptions();
        // Duplicate keys are caught below, on the tuples, so that the message can name the key
        // and its line. SnakeYAML's own check runs in the constructor, which compose() does not
        // reach.
        options.setAllowDuplicateKeys(true);

        Iterable<Node> documents;
        try {
            documents = new Yaml(options).composeAll(new StringReader(text));
            List<Node> roots = new ArrayList<>();
            for (Node document : documents) {
                roots.add(document);
            }
            if (roots.isEmpty()) {
                throw new YamlException(Location.unknown(file),
                        "the file is empty; expected a deployment document");
            }
            if (roots.size() > 1) {
                throw new YamlException(mark(roots.get(1).getStartMark(), file),
                        "a second YAML document; a deployment file describes one deployment");
            }
            return convert(roots.get(0), file);
        } catch (MarkedYAMLException error) {
            // SnakeYAML's own message is several lines with an excerpt of the source in the
            // middle of it, which reads badly once it is one line of a report. The problem alone
            // plus our own location is what a reader needs.
            throw new YamlException(mark(error.getProblemMark(), file),
                    error.getProblem() == null ? "the document is not valid YAML" : error.getProblem());
        } catch (YAMLException error) {
            throw new YamlException(Location.unknown(file),
                    error.getMessage() == null ? "the document is not valid YAML" : error.getMessage());
        }
    }

    private static YamlNode convert(Node node, String file) {
        Location location = mark(node.getStartMark(), file);
        if (node instanceof MappingNode mapping) {
            Map<String, YamlNode> entries = new LinkedHashMap<>();
            Map<String, Location> keys = new LinkedHashMap<>();
            for (NodeTuple tuple : mapping.getValue()) {
                Node keyNode = tuple.getKeyNode();
                Location keyLocation = mark(keyNode.getStartMark(), file);
                if (!(keyNode instanceof ScalarNode scalar)) {
                    throw new YamlException(keyLocation,
                            "a key that is not a plain name; expected a field name");
                }
                String key = scalar.getValue();
                if (entries.containsKey(key)) {
                    throw new YamlException(keyLocation, "duplicate key '" + key
                            + "'; the second one would silently win");
                }
                keys.put(key, keyLocation);
                entries.put(key, convert(tuple.getValueNode(), file));
            }
            return new YamlNode.Mapping(entries, keys, location);
        }
        if (node instanceof SequenceNode sequence) {
            List<YamlNode> items = new ArrayList<>(sequence.getValue().size());
            for (Node item : sequence.getValue()) {
                items.add(convert(item, file));
            }
            return new YamlNode.Sequence(List.copyOf(items), location);
        }
        ScalarNode scalar = (ScalarNode) node;
        if (Tag.NULL.equals(scalar.getTag())) {
            return new YamlNode.Null(location);
        }
        return new YamlNode.Scalar(scalar.getValue(), scalar.getTag().getValue(), location);
    }

    private static Location mark(Mark mark, String file) {
        // SnakeYAML counts from zero; every editor and every error message counts from one.
        return mark == null ? Location.unknown(file)
                : new Location(file, mark.getLine() + 1, mark.getColumn() + 1);
    }
}
