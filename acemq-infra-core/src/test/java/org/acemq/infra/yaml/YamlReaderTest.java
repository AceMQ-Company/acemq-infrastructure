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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The tree, its marks, and the three shapes the reader refuses outright. */
class YamlReaderTest {

    @Test
    void keepsTheLineOfEveryKey() {
        YamlNode.Mapping root = (YamlNode.Mapping) YamlReader.read("""
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: orders
                """, "orders.yaml");

        assertThat(root.keyLocation("apiVersion").line()).isEqualTo(1);
        assertThat(root.keyLocation("kind").line()).isEqualTo(2);
        assertThat(root.keyLocation("metadata").line()).isEqualTo(3);

        YamlNode.Mapping metadata = (YamlNode.Mapping) root.get("metadata").orElseThrow();
        assertThat(metadata.keyLocation("name").line()).isEqualTo(4);
        assertThat(metadata.keyLocation("name").file()).isEqualTo("orders.yaml");
    }

    @Test
    void keepsTheKeysInTheOrderTheFileWritesThem() {
        YamlNode.Mapping root = (YamlNode.Mapping) YamlReader.read(
                "zebra: 1\napple: 2\nmiddle: 3\n", "f.yaml");

        assertThat(root.keys()).containsExactly("zebra", "apple", "middle");
    }

    /** The tag is what separates {@code depth: 0} from {@code depth: "0"}. */
    @Test
    void remembersWhetherAScalarWasQuoted() {
        YamlNode.Mapping root = (YamlNode.Mapping) YamlReader.read(
                "number: 0\nquoted: \"0\"\nflag: true\nword: true-ish\n", "f.yaml");

        assertThat(((YamlNode.Scalar) root.get("number").orElseThrow()).isInteger()).isTrue();
        assertThat(((YamlNode.Scalar) root.get("quoted").orElseThrow()).isInteger()).isFalse();
        assertThat(((YamlNode.Scalar) root.get("flag").orElseThrow()).isBoolean()).isTrue();
        assertThat(((YamlNode.Scalar) root.get("word").orElseThrow()).isBoolean()).isFalse();
    }

    /** A key with nothing after it is a distinct kind of nothing, not a scalar holding null. */
    @Test
    void readsAnExplicitNullAsNull() {
        YamlNode.Mapping root = (YamlNode.Mapping) YamlReader.read("caFile:\nother: ~\n", "f.yaml");

        assertThat(root.get("caFile").orElseThrow()).isInstanceOf(YamlNode.Null.class);
        assertThat(root.get("other").orElseThrow()).isInstanceOf(YamlNode.Null.class);
        assertThat(root.has("caFile")).isTrue();
    }

    @Test
    void readsSequences() {
        YamlNode.Mapping root = (YamlNode.Mapping) YamlReader.read(
                "queues: [\"orders.*\", \"!orders.audit\"]\n", "f.yaml");

        YamlNode.Sequence queues = (YamlNode.Sequence) root.get("queues").orElseThrow();
        assertThat(queues.items()).hasSize(2);
        assertThat(((YamlNode.Scalar) queues.items().get(1)).text()).isEqualTo("!orders.audit");
    }

    @Test
    void namesTheLineOfASyntaxError() {
        assertThatThrownBy(() -> YamlReader.read("""
                apiVersion: acemq.org/v1alpha1
                metadata:
                  name: orders
                   indented: wrong
                """, "orders.yaml"))
                .isInstanceOf(YamlException.class)
                .hasMessageContaining("orders.yaml:4");
    }

    /**
     * A duplicated key would silently run under whichever copy came second, and the field most
     * likely to be duplicated during an edit is the one docs/configuration.md refuses to let the
     * tool decide.
     */
    @Test
    void refusesADuplicateKey() {
        assertThatThrownBy(() -> YamlReader.read(
                "deployment:\n  semantics: atLeastOnce\n  semantics: atMostOnce\n", "orders.yaml"))
                .isInstanceOf(YamlException.class)
                .hasMessageContaining("orders.yaml:3")
                .hasMessageContaining("duplicate key 'semantics'");
    }

    @Test
    void refusesASecondDocument() {
        assertThatThrownBy(() -> YamlReader.read("kind: Deployment\n---\nkind: Deployment\n",
                "orders.yaml"))
                .isInstanceOf(YamlException.class)
                .hasMessageContaining("a second YAML document");
    }

    @Test
    void refusesAnEmptyFile() {
        assertThatThrownBy(() -> YamlReader.read("# nothing but a comment\n", "orders.yaml"))
                .isInstanceOf(YamlException.class)
                .hasMessageContaining("the file is empty");
    }

    @Test
    void refusesAKeyThatIsNotAName() {
        assertThatThrownBy(() -> YamlReader.read("? [a, b]\n: value\n", "orders.yaml"))
                .isInstanceOf(YamlException.class)
                .hasMessageContaining("expected a field name");
    }

    /** A leading document marker after a comment block is how every example starts. */
    @Test
    void readsADocumentThatStartsWithAMarker() {
        YamlNode node = YamlReader.read("# a comment\n---\nkind: Deployment\n", "f.yaml");

        assertThat(node).isInstanceOf(YamlNode.Mapping.class);
        assertThat(((YamlNode.Mapping) node).keys()).containsExactly("kind");
    }
}
