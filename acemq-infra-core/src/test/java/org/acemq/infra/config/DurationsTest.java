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

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code 15m} / {@code 72h} form, which is the only form the deployment file writes. */
class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "500ms, PT0.5S",
            "90s, PT1M30S",
            "15m, PT15M",
            "2h, PT2H",
            "72h, PT72H",
            "7d, PT168H",
            "1h30m, PT1H30M",
            "1d2h3m4s, PT26H3M4S",
    })
    void parsesTheFormTheFileWrites(String text, String expected) {
        assertThat(Durations.parse(text)).contains(Duration.parse(expected));
    }

    /**
     * "15 minutes" is the interesting one. A regex that merely searched for terms would find the
     * {@code m} inside {@code minutes}, come back with fifteen milliseconds and leave a drain with
     * a timeout wrong by a factor of nine hundred thousand.
     */
    @ParameterizedTest
    @ValueSource(strings = {"15 minutes", "fifteen", "15", "m", "15x", "-5m", "PT15M", "1h 30m", ""})
    void refusesAnythingElse(String text) {
        assertThat(Durations.parse(text)).isEmpty();
    }

    @Test
    void refusesNothingAtAll() {
        assertThat(Durations.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "PT15M, 15m",
            "PT72H, 3d",
            "PT1H30M, 1h30m",
            "PT0.25S, 250ms",
            "PT0S, 0s",
    })
    void printsItBackInTheSameForm(String iso, String expected) {
        assertThat(Durations.format(Duration.parse(iso))).isEqualTo(expected);
    }
}
