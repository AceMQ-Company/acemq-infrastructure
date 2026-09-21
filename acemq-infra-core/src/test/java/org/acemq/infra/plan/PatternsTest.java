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
package org.acemq.infra.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a queue list selects.
 *
 * <p>Worth its own test because the number in {@code 29 queues to move} is the number an operator
 * reads to decide whether the plan matches what they think their estate is, and a pattern that
 * quietly matches one queue too many is a plan that reads correctly and shovels somebody's audit
 * trail.
 */
class PatternsTest {

    private static final List<String> QUEUES = List.of("orders.new", "orders.audit",
            "orders.notifications", "ordersXaudit", "orders", "billing.new");

    private static List<String> select(String... patterns) {
        return Patterns.select(QUEUES, List.of(patterns), Function.identity());
    }

    @Test
    @DisplayName("an empty list is everything, because a drain with no queues named drains it all")
    void emptyIsEverything() {
        assertThat(select()).isEqualTo(QUEUES);
    }

    @Test
    @DisplayName("a name with no wildcard matches only itself")
    void exact() {
        assertThat(select("orders.new")).containsExactly("orders.new");
    }

    @Test
    @DisplayName("a star matches a run of anything")
    void star() {
        assertThat(select("orders.*")).containsExactly("orders.new", "orders.audit",
                "orders.notifications");
    }

    @Test
    @DisplayName("a dot is a dot, which is the whole reason these are globs")
    void dotIsLiteral() {
        // Under a regular expression `orders.*` would take ordersXaudit and `orders` with it. A
        // file's author writing that line means the orders family and would be astonished by
        // either, and the one it would take is an audit queue.
        assertThat(select("orders.*")).doesNotContain("ordersXaudit", "orders");
    }

    @Test
    @DisplayName("a bang excludes, and an exclusion beats an inclusion")
    void exclusion() {
        assertThat(select("orders.*", "!orders.audit"))
                .containsExactly("orders.new", "orders.notifications");
    }

    @Test
    @DisplayName("a list of nothing but exclusions means everything else")
    void exclusionsOnly() {
        assertThat(select("!orders.audit")).doesNotContain("orders.audit")
                .hasSize(QUEUES.size() - 1);
    }

    @Test
    @DisplayName("several inclusions are an or")
    void severalInclusions() {
        assertThat(select("orders.new", "billing.*"))
                .containsExactly("orders.new", "billing.new");
    }

    @Test
    @DisplayName("a question mark matches one character")
    void questionMark() {
        assertThat(Patterns.select(List.of("q1", "q2", "q10"), List.of("q?"),
                Function.identity())).containsExactly("q1", "q2");
    }

    @Test
    @DisplayName("names what an exclusion took back out, which is what the plan prints")
    void excludedNames() {
        assertThat(Patterns.excludedNames(QUEUES, List.of("orders.*", "!orders.audit"),
                Function.identity())).containsExactly("orders.audit");
    }

    @Test
    @DisplayName("does not report a name that was never included in the first place")
    void excludedButNeverIncluded() {
        assertThat(Patterns.excludedNames(QUEUES, List.of("orders.new", "!billing.new"),
                Function.identity())).isEmpty();
    }
}
