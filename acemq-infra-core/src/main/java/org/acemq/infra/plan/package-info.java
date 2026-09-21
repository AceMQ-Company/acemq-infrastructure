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
 * What a cutover would do, worked out without doing any of it.
 *
 * <p>Three things live here and they are in the same module as the configuration model and the
 * validator, which is the arrangement that makes the milestone's last clause structural rather
 * than careful. {@link org.acemq.infra.plan.DefaultSteps} is the step list each operation gets
 * when the file does not write one, in the order docs/blue-green.md argues for.
 * {@link org.acemq.infra.plan.Planner} turns a validated file and two
 * {@link org.acemq.infra.provider.ProbedCluster} values into a {@link org.acemq.infra.plan.Plan}.
 * The plan renders itself.
 *
 * <p>Nothing in this package can reach a broker. It is not that nothing does: nothing
 * <em>can</em>, because the module this is in has no broker client on its classpath and the
 * RabbitMQ provider is downstream of it. The dependency direction is the enforcement.
 */
package org.acemq.infra.plan;
