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
import java.util.List;
import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * How clients are moved from one cluster to the other, as a top-level block.
 *
 * <p>The block exists because the thing it describes had no name in the old format. Clients find
 * the live cluster through DNS, a load balancer, a mesh or a connection string, none of which this
 * tool owns; what it can do is stop at exactly the right moment and say so, or run a command the
 * estate wrote.
 *
 * <p>{@code description} is required for {@link EndpointKind#EXTERNAL} for a reason that is easy
 * to miss: it is not documentation, it is the text a human is shown when the plan stops and waits
 * for them. An external endpoint with no description stops the cutover with nothing on the screen
 * to act on.
 *
 * @param kind external or hook; absent when the file did not say, which the validator refuses
 * @param description what a human is told when an external switch stops the plan
 * @param run the command a hook runs
 * @param args its arguments, where {@code {{target}}} is substituted
 * @param timeout how long the hook may take
 * @param location where the {@code endpoint:} block is written
 */
public record Endpoint(Optional<EndpointKind> kind, Optional<String> description,
                       Optional<String> run, List<String> args, Optional<Duration> timeout,
                       Location location) {
}
