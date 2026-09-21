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

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code 15m} / {@code 72h} form the deployment file writes its timeouts in.
 *
 * <p>Not {@link java.time.Duration#parse}, which wants {@code PT15M} — nobody writes a rollback
 * window as {@code PT72H} and a format that demanded it would be a format people get wrong.
 *
 * <p>Units go no finer than milliseconds and no coarser than days. A day is 24 hours here, which
 * is a simplification a calendar would disagree with; the values these fields hold are drain
 * timeouts and rollback windows, so an hour either side of a daylight-saving boundary is not a
 * thing anybody is relying on, and saying so is better than pretending the arithmetic is
 * calendar-aware.
 */
public final class Durations {

    private static final Pattern TERM = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * @param text something like {@code 15m}, {@code 500ms} or {@code 1h30m}
     * @return the duration, or empty if the text is not in that form
     */
    public static Optional<Duration> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TERM.matcher(text.trim());
        Duration total = Duration.ZERO;
        int consumed = 0;
        while (matcher.find()) {
            // A term has to start where the last one ended. Without this, "15 minutes" would
            // parse as 15 milliseconds by finding the "m" in "minutes" and ignoring the rest,
            // which is a timeout that is wrong by a factor of nine hundred thousand.
            if (matcher.start() != consumed) {
                return Optional.empty();
            }
            consumed = matcher.end();
            long amount = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable unit " + matcher.group(2));
            });
        }
        return consumed == text.trim().length() && consumed > 0 ? Optional.of(total) : Optional.empty();
    }

    /**
     * The same form on the way out, so that a plan prints {@code 15m} rather than
     * {@code PT15M}.
     */
    public static String format(Duration duration) {
        long seconds = duration.getSeconds();
        int millis = duration.getNano() / 1_000_000;
        if (seconds == 0 && millis > 0) {
            return millis + "ms";
        }
        StringBuilder text = new StringBuilder();
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;
        if (days > 0) {
            text.append(days).append('d');
        }
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (minutes > 0) {
            text.append(minutes).append('m');
        }
        if (remainder > 0 || text.length() == 0) {
            text.append(remainder).append('s');
        }
        if (millis > 0) {
            text.append(millis).append("ms");
        }
        return text.toString();
    }
}
