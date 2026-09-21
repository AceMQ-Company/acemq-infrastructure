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
 * The RabbitMQ provider: one verb, implemented against a real broker.
 *
 * <p>{@link org.acemq.infra.provider.rabbitmq.RabbitProbe} answers docs/broker-agnostic.md's first
 * question — what can this cluster actually do — and the eight verbs beside it are not here,
 * because they all write and this phase does not. They arrive with the executor in phase 2, each
 * one declared in the same change as its implementation.
 *
 * <p>Everything in this package is package-private except the probe. There is one public entry
 * point because there is one thing a caller may do, and the rest — the read-only client, the
 * endpoint check, the version and tag arithmetic — are how it is done rather than what it offers.
 */
package org.acemq.infra.provider.rabbitmq;
