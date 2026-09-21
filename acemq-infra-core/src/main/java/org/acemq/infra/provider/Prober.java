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
package org.acemq.infra.provider;

/**
 * The first of the nine verbs: what can this cluster actually do?
 *
 * <p>An interface with one method, and the eight it will eventually sit beside deliberately
 * absent. docs/broker-agnostic.md warns about the interface that is extracted first and
 * implemented afterwards — eighty methods derived from one broker's management API, sixty of which
 * throw on the second. This is the other order: the verb is declared in the same change as the
 * RabbitMQ implementation of it, and each of the remaining eight will be too.
 *
 * <p>It is an interface rather than a static call for one immediate reason. It is the only place
 * where {@code plan} touches a broker, so it is the only thing a test of the command itself has to
 * replace, and a command that can be run end to end against a constructed cluster is a command
 * whose output is tested rather than eyeballed.
 */
@FunctionalInterface
public interface Prober {

    /**
     * Asks a cluster what it is and what it can do, without changing anything about it.
     *
     * <p>Every implementation of this reads and only reads. That is not a convention this
     * interface can enforce — a method returning a value is free to have had side effects — so the
     * RabbitMQ implementation makes it structural in the only place it can be made structural,
     * which is at the client it holds.
     *
     * @param access how to reach the cluster
     * @return what was found
     */
    ProbedCluster probe(ClusterAccess access);
}
