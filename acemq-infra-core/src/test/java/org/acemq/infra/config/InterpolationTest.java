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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code ${VAR}} substitution, and the one behaviour that is a deliberate break with the format
 * this inherits: an unset variable is fatal.
 */
class InterpolationTest {

    private static final Environment ENVIRONMENT = Environment.of(Map.of(
            "BLUE_PASSWORD", "s3cret",
            "GREEN_PASSWORD", "als0-s3cret",
            "EMPTY", ""));

    @Test
    void substitutesWhatIsSet() {
        String result = Interpolation.apply("password: ${BLUE_PASSWORD}", "f.yaml", ENVIRONMENT);

        assertThat(result).isEqualTo("password: s3cret");
    }

    @Test
    void substitutesEveryOccurrence() {
        String result = Interpolation.apply(
                "a: ${BLUE_PASSWORD}\nb: ${GREEN_PASSWORD}\nc: ${BLUE_PASSWORD}\n",
                "f.yaml", ENVIRONMENT);

        assertThat(result).isEqualTo("a: s3cret\nb: als0-s3cret\nc: s3cret\n");
    }

    /**
     * A variable that is set to the empty string is set. The refusal is about a variable that is
     * <em>absent</em> — an operator who deliberately exported an empty value has made a choice,
     * and second-guessing it would be a different tool.
     */
    @Test
    void anEmptyValueIsAValue() {
        assertThat(Interpolation.apply("caFile: ${EMPTY}", "f.yaml", ENVIRONMENT))
                .isEqualTo("caFile: ");
    }

    @Test
    void leavesTextThatIsNotAReferenceAlone() {
        String text = "payload: \"$notavar {braces} $ {VAR} ${lowercase-invalid}\"";

        assertThat(Interpolation.apply(text, "f.yaml", ENVIRONMENT)).isEqualTo(text);
    }

    /**
     * The whole point of the class. The old behaviour left the literal {@code ${BLUE_PASSWORD}} in
     * the file, which parsed, planned and then failed authenticating partway through a cutover.
     */
    @Test
    void refusesAnUnsetVariable() {
        assertThatThrownBy(() ->
                Interpolation.apply("password: ${NOT_SET}", "orders.yaml", ENVIRONMENT))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("orders.yaml:1")
                .hasMessageContaining("${NOT_SET}")
                .hasMessageContaining("is not set in this environment");
    }

    /** Every unset variable at once: one run per missing secret would be four runs. */
    @Test
    void reportsEveryUnsetVariableTogether() {
        ConfigException error = catchThrowableOfType(() -> Interpolation.apply(
                "a: ${ONE}\nb: ${BLUE_PASSWORD}\nc: ${TWO}\nd: ${THREE}\n",
                "orders.yaml", ENVIRONMENT), ConfigException.class);

        assertThat(error.problems()).extracting(ConfigException.Problem::path)
                .containsExactly("${ONE}", "${TWO}", "${THREE}");
    }

    /** A variable used four times is one thing to fix, so it is reported once — at the first use. */
    @Test
    void reportsARepeatedUnsetVariableOnce() {
        ConfigException error = catchThrowableOfType(() -> Interpolation.apply(
                "a: x\nb: ${MISSING}\nc: ${MISSING}\n", "orders.yaml", ENVIRONMENT),
                ConfigException.class);

        assertThat(error.problems()).hasSize(1);
        assertThat(error.problems().get(0).location().line()).isEqualTo(2);
    }

    @Test
    void namesTheLineTheReferenceIsOn() {
        ConfigException error = catchThrowableOfType(() -> Interpolation.apply(
                "one\ntwo\nthree\nfour: ${MISSING}\n", "orders.yaml", ENVIRONMENT),
                ConfigException.class);

        assertThat(error.problems().get(0).location().describe()).isEqualTo("orders.yaml:4");
    }

    /** A value with a backslash or a dollar in it is inserted as written, not as a regex. */
    @Test
    void substitutesValuesWithReplacementSyntaxInThem() {
        Environment awkward = Environment.of(Map.of("PASSWORD", "a\\b$1c"));

        assertThat(Interpolation.apply("p: ${PASSWORD}", "f.yaml", awkward))
                .isEqualTo("p: a\\b$1c");
    }

    @Test
    void listsTheReferencesItFinds() {
        assertThat(Interpolation.references("a: ${ONE}\nb: ${TWO}\nc: ${ONE}"))
                .containsExactly("ONE", "TWO");
    }
}
