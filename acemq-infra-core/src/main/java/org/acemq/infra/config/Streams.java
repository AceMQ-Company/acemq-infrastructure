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

import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * The {@code streams:} block — the operator's acknowledgement of what a cutover does to stream
 * consumers.
 *
 * <p>Stream offsets do not travel between clusters. RabbitMQ has no mechanism for moving them, so
 * the tool's whole contribution is to say what will happen to each consumer and make somebody
 * confirm it before the plan proceeds.
 *
 * <p>This block is underspecified in the documentation as it stands, and this record reflects
 * exactly what the documentation says rather than what a full design would want.
 * scripts/lint-deployment.py accepts {@code streams} as a top-level key and checks nothing inside
 * it; docs/roadmap.md's worked plan output says "streams.acknowledged is set"; docs/canary.md
 * calls it "the {@code streams.acknowledged} confirmation". No page writes the block out. A single
 * boolean is the smallest thing that satisfies all three, and it is modelled rather than
 * invented — the per-consumer projection those pages describe arrives with stream handling in
 * phase 3, and this field will need revisiting then.
 *
 * @param acknowledged whether the operator has confirmed what will happen to the offsets
 * @param location where the {@code streams:} block is written
 */
public record Streams(Optional<Boolean> acknowledged, Location location) {
}
