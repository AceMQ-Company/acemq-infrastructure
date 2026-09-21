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

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.acemq.infra.yaml.Location;

/**
 * The {@code mirror:} block on a deployment: what is copied, and from where.
 *
 * <p>Exchanges, and the word matters. See {@link Action.Mirror} and docs/message-state.md — the
 * same refusal applies to this block and to the step's, and the validator runs the same check over
 * both.
 *
 * @param exchanges the exchanges to federate
 * @param queues present only so that asking for queue federation can be refused with a reason
 * @param upstream how the target reaches the source
 * @param location where the {@code mirror:} block is written
 */
public record MirrorSpec(List<String> exchanges, List<String> queues, Optional<Upstream> upstream,
                         Location location) {

    /**
     * How the mirroring cluster reaches the one it is copying from.
     *
     * @param uri the source's AMQP URI, as the <em>target broker</em> will dial it
     * @param prefetch how many messages the federation link takes at a time
     * @param ackMode the link's acknowledgement mode, kept as written
     * @param location where {@code upstream:} is written
     */
    public record Upstream(Optional<String> uri, OptionalInt prefetch, Optional<String> ackMode,
                           Location location) {
    }
}
