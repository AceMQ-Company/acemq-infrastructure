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
package org.acemq.infra.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * What a queue pattern selects when a shovel is about to be declared for each answer.
 *
 * <p>The same cases {@code PatternsTest} asserts in the core module, deliberately repeated here.
 * The two implementations exist side by side because the alternative is for the planner's package
 * to grow a caller in the module that writes, and the whole enforcement of "plan cannot write" is
 * that nothing in the writing direction is reachable from it. What holds them in agreement is that
 * both suites run the documented examples — {@code ["orders.*", "!orders.audit"]} is in three
 * pages — so a divergence is a red build rather than a queue nobody drained.
 */
class SelectionTest {

    private static final List<String> QUEUES = List.of("orders.new", "orders.notifications",
            "orders.audit", "orders.priority", "billing.invoices", "orders");

    private static List<String> selected(String... patterns) {
        return Selection.select(QUEUES, List.of(patterns), Function.identity());
    }

    @Test
    void anEmptyListMeansAllOfThem() {
        assertThat(selected()).isEqualTo(QUEUES);
    }

    @Test
    void anExclusionWins() {
        assertThat(selected("orders.*", "!orders.audit"))
                .containsExactly("orders.new", "orders.notifications", "orders.priority");
    }

    @Test
    void aStarIsAGlobAndADotIsADot() {
        // Under a regular expression `orders.*` would also take `orders` and would take
        // `ordersXaudit` if there were one, the dot being a wildcard. The one it would wrongly
        // take here is an audit queue, which is exactly the queue a file excludes on purpose.
        assertThat(selected("orders.*")).doesNotContain("orders");
        assertThat(selected("orders.*")).doesNotContain("billing.invoices");
    }

    @Test
    void anExclusionOnItsOwnMeansAllOfItButThat() {
        assertThat(selected("!billing.*"))
                .containsExactly("orders.new", "orders.notifications", "orders.audit",
                        "orders.priority", "orders");
    }

    @Test
    void anExactNameSelectsExactlyIt() {
        assertThat(selected("orders.new")).containsExactly("orders.new");
    }
}
