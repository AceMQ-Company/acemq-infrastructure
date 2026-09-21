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
 * The deployment file: its model and its parser.
 *
 * <p>The file is the interface. docs/configuration.md is the specification and these records are
 * that document in Java — where the two disagree, the document is right and this package has a
 * bug.
 *
 * <p>Start at {@link org.acemq.infra.config.DeploymentFiles#load} for a path,
 * {@link org.acemq.infra.config.DeploymentFileParser#parse} for text. Both hand back a
 * {@link org.acemq.infra.config.DeploymentFile} or throw a
 * {@link org.acemq.infra.config.ConfigException}, whose javadoc explains exactly which failures
 * land here and which are left to {@code org.acemq.infra.validate}.
 */
package org.acemq.infra.config;
