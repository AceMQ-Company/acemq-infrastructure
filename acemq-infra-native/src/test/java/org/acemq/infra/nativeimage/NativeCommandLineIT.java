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
package org.acemq.infra.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Everything the binary does without a broker, asserted against the binary.
 *
 * <p>{@code CliTest} covers this surface against constructed clusters and covers it more thoroughly
 * than this ever will. What it cannot cover is anything that is only true of a process: the exit
 * codes a shell sees, the version string, whether the examples this repository publishes are
 * actually accepted by the thing people download, and whether the files in {@code examples/rejected}
 * are still rejected by it.
 *
 * <p>That last pair is the one worth having. {@code ci.yml} asserts both against
 * {@code scripts/lint-deployment.py}, one file at a time, because a linter whose rules have quietly
 * stopped firing passes everything including the files that describe the mistakes it exists to
 * catch. The Java validator's own suite asserts it too. Neither of them is what a user runs.
 */
@DisplayName("the binary, with no broker anywhere near it")
class NativeCommandLineIT {

    /** Where the repository's examples are, handed over by the build rather than guessed at. */
    private static Path examples() {
        String configured = System.getProperty("acemq.infra.examples");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("acemq.infra.examples is not set; the build has to say"
                    + " where examples/ is, because this module is not where it lives.");
        }
        return Path.of(configured);
    }

    private static List<Path> accepted() {
        return yamlIn(examples());
    }

    private static List<Path> rejected() {
        return yamlIn(examples().resolve("rejected"));
    }

    private static List<Path> yamlIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> found = files.filter(file -> file.toString().endsWith(".yaml")).sorted()
                    .toList();
            if (found.isEmpty()) {
                // An empty list would turn every assertion below into a test that runs nothing and
                // reports green, which is the same failure a skip is.
                throw new IllegalStateException("no .yaml files under " + directory);
            }
            return found;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    // ---------------------------------------------------------------- the process itself

    @Test
    @Timeout(120)
    @DisplayName("reports a version, and not the word it prints when there is no jar")
    void theVersion() {
        Binary.Result version = Binary.run("--version");

        assertThat(version.status()).isZero();
        assertThat(version.out()).startsWith("acemq-infra ");
        // The version is read out of the jar's manifest through Package.getImplementationVersion,
        // which is a jar-shaped question being asked of something that is not a jar. It was
        // expected to come back empty here and it does not: native-image carries the manifest's
        // implementation version into the image. Asserted rather than assumed, because a release
        // that shipped a binary answering "(from classes, no version)" would be a release nobody
        // could identify afterwards.
        assertThat(version.out()).doesNotContain("no version");
    }

    @Test
    @Timeout(120)
    @DisplayName("prints the usage and succeeds when asked for help")
    void theHelp() {
        Binary.Result help = Binary.run("--help");

        assertThat(help.status()).isZero();
        assertThat(help.out()).contains("acemq-infra validate", "acemq-infra plan",
                "acemq-infra apply");
    }

    @Test
    @Timeout(120)
    @DisplayName("exits 2 for a command line it cannot make sense of")
    void theCommandLine() {
        assertThat(Binary.run().status()).isEqualTo(2);
        assertThat(Binary.run("rollback", "-f", "whatever.yaml").status()).isEqualTo(2);
        assertThat(Binary.run("validate").status()).isEqualTo(2);
        // Refused rather than ignored, because a reading command that accepted an apply flag would
        // be a command somebody believes they have dry-run.
        assertThat(Binary.run("plan", "-f", "whatever.yaml", "--dry-run").status()).isEqualTo(2);
        assertThat(Binary.run("apply", "-f", "whatever.yaml", "--yes", "--dry-run").status())
                .isEqualTo(2);
    }

    @Test
    @Timeout(120)
    @DisplayName("says which file it could not read, rather than throwing something out of a stack")
    void theMissingFile() {
        Binary.Result missing = Binary.run("validate", "-f", "no-such-deployment.yaml");

        assertThat(missing.status()).isEqualTo(2);
        assertThat(missing.err()).contains("no-such-deployment.yaml");
        // A native image reports an uncaught exception as a stack trace with no message worth
        // reading. Every ordinary failure has to be an ordinary message.
        assertThat(missing.all()).doesNotContain("Exception in thread");
    }

    // ---------------------------------------------------------------- the configuration format

    @ParameterizedTest(name = "{0} validates")
    @MethodSource("accepted")
    @Timeout(120)
    @DisplayName("accepts every example this repository publishes")
    void everyExampleValidates(Path example) {
        Binary.Result validated = Binary.run("validate", "-f", example.toString());

        assertThat(validated.status()).isZero();
        assertThat(validated.out()).contains("ok —");
    }

    @ParameterizedTest(name = "{0} is rejected")
    @MethodSource("rejected")
    @Timeout(120)
    @DisplayName("rejects every example that is deliberately wrong")
    void everyRejectedExampleIsRejected(Path example) {
        Binary.Result validated = Binary.run("validate", "-f", example.toString());

        // One file at a time, so that a single one silently starting to pass cannot hide behind
        // another that still fails.
        assertThat(validated.status())
                .withFailMessage("%s was accepted by the binary. It is deliberately wrong, so a"
                        + " rule has stopped firing — or has stopped being reachable in the native"
                        + " image, which is the same outcome by a different route.", example)
                .isNotZero();
    }
}
