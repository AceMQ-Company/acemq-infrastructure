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
 * The command line: {@code validate}, {@code plan}, and nothing else.
 *
 * <p>A thin reader of the file, which is the jreleaser lesson docs/shape.md takes: the
 * configuration file is the interface and the CLI is a way of reading it. Almost nothing here is
 * logic — the rules are in the validator, the order is in the planner, and the one thing that
 * belongs to this layer is the decision {@link org.acemq.infra.cli.Variables} argues, about what
 * an unset {@code ${VAR}} means to a command that is never going to connect to anything.
 */
package org.acemq.infra.cli;
