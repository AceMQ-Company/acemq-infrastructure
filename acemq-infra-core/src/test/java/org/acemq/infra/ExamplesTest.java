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
package org.acemq.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFiles;
import org.acemq.infra.config.Interpolation;
import org.acemq.infra.validate.Finding;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The worked examples, against the Java validator.
 *
 * <p>This is the test that makes the rest of them worth having. {@code examples/} illustrates
 * docs/configuration.md and {@code examples/rejected/} describes, file by file and in its own
 * comments, the mistakes that cost messages; if the validator stops agreeing with those six files
 * then either the format has moved or a rule has quietly stopped firing, and the second is
 * indistinguishable from a validator that works.
 *
 * <p>The rejections are asserted by message and by field, not by "it was refused". A rejected file
 * with three deliberate mistakes in it will still be refused when two of the three rules have been
 * broken, and that is exactly the state this suite has to be able to see.
 */
class ExamplesTest {

    /** Where the fixtures live. Set by Surefire; ../examples is what an IDE run gets. */
    private static final Path EXAMPLES =
            Path.of(System.getProperty("acemq.infra.examples", "../examples"));

    @ParameterizedTest(name = "{0} is accepted — {1}")
    @CsvSource({
            "blue-green.yaml, 'blueGreen, 9 steps, 2 clusters'",
            "canary.yaml, 'canary, 9 steps, 2 clusters'",
            "mirror.yaml, 'mirror, 4 steps, 2 clusters'",
    })
    void acceptsTheWorkedExamples(String name, String summary) {
        ValidationReport report = validate(EXAMPLES.resolve(name));

        assertThat(report.errors())
                .describedAs("errors in examples/%s", name)
                .isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.summary()).isEqualTo(summary);
    }

    /**
     * The canary everybody writes first, because it is what a canary means everywhere else.
     *
     * <p>Two findings and they are different mistakes: the percentage itself, and the missing
     * {@code services} list, which is the check that would have stopped the queue being
     * partitioned even if somebody removed the percentage.
     */
    @Test
    void refusesACanaryWithAPercentage() {
        ValidationReport report = validate(EXAMPLES.resolve("rejected/canary-percentage.yaml"));

        assertThat(report.ok()).isFalse();
        assertThat(report.errors()).extracting(Finding::where).containsExactly(
                "deployment.scope.services",
                "deployment.scope.percentage");
        assertThat(report.errors().get(0).message())
                .startsWith("is required for a canary.")
                .contains("stops a canary from partitioning the queue");
        assertThat(report.errors().get(1).message())
                .isEqualTo("'percentage' has no meaning for a broker. Splitting producers by "
                        + "percentage partitions the queue across two clusters; the unit of a "
                        + "broker canary is a whole workload (docs/canary.md)");
    }

    /**
     * The most plausible mistake in the repository, and the one that looks like it is working.
     *
     * <p>Refused twice, at the deployment's mirror block and again at the step that starts it,
     * because both of them name the mechanism and either one alone would build the accidental
     * drain.
     */
    @Test
    void refusesAMirrorBuiltFromQueueFederation() {
        ValidationReport report =
                validate(EXAMPLES.resolve("rejected/mirror-by-queue-federation.yaml"));

        assertThat(report.ok()).isFalse();
        assertThat(report.errors()).extracting(Finding::where).containsExactly(
                "deployment.mirror",
                "deployment.steps[1](start-mirror)");
        assertThat(report.errors()).extracting(Finding::message).allSatisfy(message ->
                assertThat(message).isEqualTo(
                        "mirror.queues asks for queue federation, which pulls only when the "
                                + "upstream has no local consumers — a conditional MOVE, not a "
                                + "copy. A mirror federates exchanges: use mirror.exchanges "
                                + "(docs/message-state.md)"));
    }

    /** Three separate mistakes, each of which costs messages. All three are found. */
    @Test
    void refusesPoliciesBeforeTheDrain() {
        ValidationReport report = validate(EXAMPLES.resolve("rejected/policies-before-drain.yaml"));

        assertThat(report.ok()).isFalse();
        assertThat(report.errors()).extracting(Finding::where).containsExactly(
                "deployment.semantics",
                "deployment.steps[0](topology)",
                "deployment.steps[1](drain-messages)");

        assertThat(report.errors().get(0).message())
                .startsWith("is required and has no default.")
                .contains("The tool will not choose this for you");
        assertThat(report.errors().get(1).message())
                .startsWith("copies policies to the target before the drain.")
                .contains("live the instant it lands and will discard the backlog on arrival");
        assertThat(report.errors().get(2).message())
                .startsWith("drains with no preceding waitFor on publishRate or unacked.")
                .contains("requeue on the SOURCE when a connection closes");
    }

    /** Every finding points at a line, which is the whole reason the parser keeps marks. */
    @Test
    void namesTheFileAndTheLine() {
        ValidationReport report = validate(EXAMPLES.resolve("rejected/policies-before-drain.yaml"));

        assertThat(report.errors()).allSatisfy(finding -> {
            assertThat(finding.location().file()).endsWith("policies-before-drain.yaml");
            assertThat(finding.location().known())
                    .describedAs("%s has no line", finding.where())
                    .isTrue();
        });
    }

    /**
     * The examples refer to exactly the variables this suite supplies.
     *
     * <p>Not housekeeping. An example that grew a new {@code ${VAR}} would otherwise fail every
     * test in this class with an interpolation error and leave somebody reading a stack trace to
     * work out which file and which variable; and one that stopped using a variable would leave a
     * value here that proves nothing.
     */
    @Test
    void theExamplesReferToTheVariablesTheSuiteSets() {
        Set<String> referenced = new LinkedHashSet<>();
        for (Path file : examples()) {
            referenced.addAll(Interpolation.references(read(file)));
        }

        assertThat(referenced).containsExactlyInAnyOrderElementsOf(Fixtures.VARIABLES.keySet());
    }

    /** Every example parses into a model that knows which file it came from. */
    @Test
    void everyExampleParses() {
        for (Path file : examples()) {
            DeploymentFile parsed = DeploymentFiles.load(file, Fixtures.ENVIRONMENT);
            assertThat(parsed.origin()).isEqualTo(file.toString());
            assertThat(parsed.apiVersion()).contains(DeploymentFile.API_VERSION);
            assertThat(parsed.kind()).contains(DeploymentFile.KIND);
        }
    }

    private static ValidationReport validate(Path file) {
        return Validator.validate(DeploymentFiles.load(file, Fixtures.ENVIRONMENT));
    }

    private static Iterable<Path> examples() {
        try (Stream<Path> top = Files.list(EXAMPLES);
                Stream<Path> rejected = Files.list(EXAMPLES.resolve("rejected"))) {
            return Stream.concat(top, rejected)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException("cannot list " + EXAMPLES.toAbsolutePath(), error);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + file, error);
        }
    }
}
