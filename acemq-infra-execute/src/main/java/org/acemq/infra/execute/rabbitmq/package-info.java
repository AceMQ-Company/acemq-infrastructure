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
 * The RabbitMQ provider's writing half: eight verbs, against a real broker.
 *
 * <p>Deliberately not in {@code org.acemq.infra.provider.rabbitmq}, which is where {@code probe()}
 * lives. That package contains no method that writes to a broker — not "none that are called",
 * none that exist — and the read-only wrapper the probe holds is how "plan writes nothing" stopped
 * being a discipline and became a fact. Adding the writing methods there, even behind a flag, would
 * hand the probe an object that can write and merely does not, which is the state of affairs the
 * wrapper was written to end. So the writing client is {@link
 * org.acemq.infra.execute.rabbitmq.ChangingAdmin}: a different class, with a name that says what it
 * does, in a module the probe's module does not depend on.
 *
 * <p>{@link org.acemq.infra.execute.rabbitmq.RabbitBroker} is the only public type here, as
 * {@code RabbitProbe} is the only public type there, and for the same reason: there is one thing a
 * caller may do, and the client, the raw management calls and the definitions arithmetic are how it
 * is done rather than what it offers.
 */
package org.acemq.infra.execute.rabbitmq;
