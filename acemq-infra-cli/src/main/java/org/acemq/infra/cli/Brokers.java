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
package org.acemq.infra.cli;

import org.acemq.infra.execute.Broker;
import org.acemq.infra.provider.ClusterAccess;

/**
 * Where a connection that can change a cluster comes from.
 *
 * <p>The same seam {@link org.acemq.infra.provider.Prober} is, and here for a sharper reason. The
 * prober is the one thing in {@code plan} that reaches a broker; this is the one thing in
 * {@code apply} that can change one, so replacing it makes the whole command — the argument
 * parsing, the refusals, the confirmation gate, the exit codes — a function a test can run end to
 * end without a container and without anything to break.
 *
 * <p>{@code Main} supplies {@code RabbitBroker::open} and nothing else does.
 */
@FunctionalInterface
interface Brokers {

    /**
     * Connects to a cluster with a client that can change it.
     *
     * @param access where the cluster is and who to be
     * @return the eight verbs, against that cluster
     */
    Broker open(ClusterAccess access);
}
