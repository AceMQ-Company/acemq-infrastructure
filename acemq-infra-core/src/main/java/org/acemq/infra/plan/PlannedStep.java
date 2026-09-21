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
package org.acemq.infra.plan;

import java.util.List;

import org.acemq.infra.provider.Capability;

/**
 * One numbered line of the plan, and the lines underneath it.
 *
 * <p>The step keeps its {@code id} from the file rather than being renumbered into a description,
 * because docs/configuration.md gives every step an id precisely so that the plan output, the
 * status output and the error messages can all refer to the same thing. A reader who sees
 * {@code 6 drain-messages} can find that step in the file by searching for the word.
 *
 * <p>{@code needs} is not printed when everything in it is present, and that is deliberate. A plan
 * that repeats nine lines of capabilities which are all satisfied has buried the one that is not.
 * It is carried on the step so that a refusal can name the step and the capability together, which
 * is the sentence docs/broker-agnostic.md asks for.
 *
 * @param number the position in the plan, counting from one and counting the backup
 * @param id the step's id, from the file or from the default list
 * @param lines what it will do, already rendered, one line each
 * @param needs the capabilities it requires, each against the cluster it requires it of
 */
public record PlannedStep(int number, String id, List<String> lines, List<Need> needs) {

    public PlannedStep {
        lines = List.copyOf(lines);
        needs = List.copyOf(needs);
    }

    /**
     * A capability a step needs, and where it needs it.
     *
     * <p>Where matters and is the thing a set of capabilities cannot express. A cutover needs
     * {@code TOPOLOGY_EXPORT} on the cluster it is reading and {@code TOPOLOGY_IMPORT_MERGE} on the
     * one it is writing, and reporting that the pair is "present" because one cluster has each is
     * how a plan passes and a cutover fails.
     *
     * @param capability what is needed
     * @param cluster the cluster it is needed on, by the file's name for it
     */
    public record Need(Capability capability, String cluster) {
    }
}
