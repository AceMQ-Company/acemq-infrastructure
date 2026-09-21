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
 * What is wrong with a deployment file, without touching a broker.
 *
 * <p>{@link org.acemq.infra.validate.Validator#validate} is a pure function: a parsed file in, a
 * {@link org.acemq.infra.validate.ValidationReport} out. Nothing here opens a connection, reads a
 * clock or looks at the filesystem, which is why the rules can be tested exhaustively and why a
 * check that has to run before anything is written does not need anything to write to.
 *
 * <p>The rules are scripts/lint-deployment.py's, and the two are held in agreement by the same
 * fixtures: everything in {@code examples/} is accepted and everything in
 * {@code examples/rejected/} is refused, by both, for the same reasons. A linter whose rules have
 * quietly stopped firing passes everything — including the files that describe the mistakes it
 * exists to catch — and looks exactly like a linter that works.
 */
package org.acemq.infra.validate;
