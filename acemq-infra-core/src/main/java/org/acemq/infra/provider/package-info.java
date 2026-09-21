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

/**
 * The provider seam: what a broker can be asked to do, and what it turns out to be able to do.
 *
 * <p>{@link org.acemq.infra.provider.Capability} is the vocabulary the validator checks a
 * {@code requires:} list against and the vocabulary a live probe answers in, and it is shared
 * deliberately — a capability spelled one way in a file and another way in a probe result is a
 * capability model that looks like it works.
 *
 * <p>One verb is here: {@link org.acemq.infra.provider.Prober}, with
 * {@link org.acemq.infra.provider.ClusterAccess} going in and
 * {@link org.acemq.infra.provider.ProbedCluster} coming out. The other eight are still not
 * written, and still not written on purpose. docs/broker-agnostic.md is an argument that a seam
 * extracted from one implementation before that implementation exists is the bad kind of seam, and
 * the argument does not weaken because one verb has now been implemented: snapshotTopology,
 * applyTopology, listAttachments, detach, drain, mirror, measure and announce all write or all
 * assume a shape nothing has yet had to fit, and they arrive with the executor in phase 2.
 *
 * <p>Nothing in this package knows a deployment file exists, which is why {@code probe()} takes a
 * {@code ClusterAccess} and not the configuration model's {@code Cluster}. The dependency runs one
 * way and the compiler holds it that way.
 */
package org.acemq.infra.provider;
