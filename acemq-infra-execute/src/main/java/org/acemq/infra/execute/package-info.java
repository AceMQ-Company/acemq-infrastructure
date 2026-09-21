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
 * The half that can break production.
 *
 * <p>Everything up to here was arranged so that it could not. {@code org.acemq.infra.plan} is in a
 * module with no broker client on its classpath; {@code org.acemq.infra.provider.rabbitmq} holds a
 * management client with the writing methods left off it. This package is the one that writes, and
 * putting it in a module of its own is what keeps the other two sentences true after phase 2: every
 * arrow still points at {@code acemq-infra-core}, so the planner is exactly as incapable of writing
 * as it was, and {@code acemq-infra-rabbitmq} still contains no writing method anywhere.
 *
 * <p>{@link org.acemq.infra.execute.Broker} is the eight verbs docs/broker-agnostic.md named and
 * phase 1 deliberately left out, each declared in the change that implements it.
 * {@link org.acemq.infra.execute.Run} describes an execution — and has no way to describe a writing
 * one without the word {@code cutover} appearing at the call site.
 * {@link org.acemq.infra.execute.Executor} carries it out, refusing the whole run before the first
 * write rather than the step in the middle of it.
 * {@link org.acemq.infra.execute.Rollbacks} works out how to get back from what actually happened
 * rather than from what was planned.
 *
 * <p>Three things in here are load-bearing and easy to mistake for ceremony.
 * {@link org.acemq.infra.execute.Guards} has three verdicts rather than two, because a condition
 * nobody can observe is not a condition that is false. {@link org.acemq.infra.execute.Rehearsal}
 * makes a dry run structurally incapable of writing rather than carefully so. And
 * {@link org.acemq.infra.execute.Console.Answer#UNATTENDED} is a different answer from "no",
 * because "the operator aborted" and "there was no operator" read identically in a log the morning
 * after and mean opposite things.
 */
package org.acemq.infra.execute;
