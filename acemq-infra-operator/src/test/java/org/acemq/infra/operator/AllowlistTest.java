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
package org.acemq.infra.operator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllowlistTest {

    @Test
    @DisplayName("the default allows in-cluster services and nothing else")
    void defaults() {
        Allowlist allowed = Allowlist.parse(null);
        assertThat(allowed.allows("http://blue.orders.svc:15672")).isTrue();
        assertThat(allowed.allows("https://blue.orders.svc.cluster.local")).isTrue();
        assertThat(allowed.allows("amqp://u:p@blue.orders.svc:5672/%2f")).isTrue();
        assertThat(allowed.allows("http://BLUE.ORDERS.SVC:15672/")).isTrue();
        assertThat(allowed.allows("http://blue:15672")).isFalse();
        assertThat(allowed.allows("http://evil.example.com:15672")).isFalse();
        assertThat(allowed.allows("http://blue.orders.svc.evil.example.com")).isFalse();
        assertThat(allowed.allows("http://127.0.0.1:15672")).isFalse();
        // Credentials in the URL do not move the host: everything up to the last @ is userinfo.
        assertThat(allowed.allows("http://x.svc:p@evil.example.com/")).isFalse();
        assertThat(allowed.allows("amqp://u:p@ss/w#rd@evil.example.com")).isFalse();
        assertThat(allowed.allows("not a url")).isFalse();
    }

    @Test
    @DisplayName("host globs, with an optional port that defaults from the scheme")
    void patterns() {
        Allowlist allowed = Allowlist.parse(" rabbit-*.prod.internal:15672 , 10.0.0.? ,mq.example.com:*");
        assertThat(allowed.allows("http://rabbit-1.prod.internal:15672")).isTrue();
        assertThat(allowed.allows("http://rabbit-1.prod.internal:15671")).isFalse();
        assertThat(allowed.allows("http://rabbit-1.prod.internal")).isFalse();
        assertThat(allowed.allows("http://10.0.0.7:15672")).isTrue();
        assertThat(allowed.allows("http://10.0.0.77:15672")).isFalse();
        assertThat(allowed.allows("https://mq.example.com:8443")).isTrue();
        assertThat(Allowlist.parse("mq.example.com:443").allows("https://mq.example.com/api"))
                .isTrue();
        assertThat(Allowlist.parse("*").allows("http://anything.at.all:1")).isTrue();
    }
}
