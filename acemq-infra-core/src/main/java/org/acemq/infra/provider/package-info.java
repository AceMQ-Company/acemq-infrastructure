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
 * <p>Half of it is here. {@link org.acemq.infra.provider.Capability} is the vocabulary the
 * validator checks a {@code requires:} list against and the vocabulary a live {@code probe()} will
 * answer in, and it is shared deliberately — a capability spelled one way in a file and another
 * way in a probe result is a capability model that looks like it works.
 *
 * <p>The other half — {@code probe()} and the eight verbs beside it — is not written yet, and it
 * is not written yet on purpose. docs/broker-agnostic.md is an argument that a seam extracted from
 * one implementation before that implementation exists is the bad kind of seam. The verbs land
 * here alongside the RabbitMQ provider that implements them, in a module that depends on this one
 * and that nothing in this one depends on.
 */
package org.acemq.infra.provider;
