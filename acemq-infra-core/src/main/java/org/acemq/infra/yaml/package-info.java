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
 * YAML with the line numbers kept.
 *
 * <p>A thin tree over SnakeYAML's composer, and the only part of this library that knows what
 * YAML is. Everything above it works in records. The single reason it exists rather than a
 * databinder call is that a deployment file is reviewed by people who did not write it, so every
 * message this tool produces has to be able to name a line — see {@link
 * org.acemq.infra.yaml.YamlNode}.
 */
package org.acemq.infra.yaml;
