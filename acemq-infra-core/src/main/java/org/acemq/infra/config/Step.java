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
package org.acemq.infra.config;

import java.util.List;
import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * One entry in the {@code steps:} list.
 *
 * <p>The explicit list is the biggest change from the format this inherits and the one that makes
 * a deployment file reviewable. The old format had three booleans — {@code useDefinitions},
 * {@code removeConnections}, {@code createShovels} — that implied an order nobody had written
 * down: the implementation knew whether the import came before or after the connections were
 * removed, and the file did not say. Worse, there is no single right order to imply, because the
 * topology import has to be split in two with the drain between the halves and no arrangement of
 * booleans expresses that.
 *
 * <p>{@code actions} is a list, not one optional action, and that is not laziness. A step with two
 * actions in it is a thing a person writes and it has to be refused with a message that says what
 * they wrote; collapsing to the first one at parse time would silently drop the second.
 *
 * @param id names this step in the plan, the status output and every error about it
 * @param actions what the step does; exactly one, which the validator enforces
 * @param waitFor the guard beside the action, or the whole of the step when it has no action
 * @param unknownKeys keys the model has no field for, kept for the validator to report
 * @param location where this list entry begins
 */
public record Step(Optional<String> id, List<Action> actions, Optional<WaitFor> waitFor,
                   List<UnknownKey> unknownKeys, Location location) {

    /** The step's single action, or empty when it has none or has too many to choose between. */
    public Optional<Action> action() {
        return actions.size() == 1 ? Optional.of(actions.get(0)) : Optional.empty();
    }

    /** The id, or {@code ?} — for building the {@code steps[3](drain-messages)} form. */
    public String describeId() {
        return id.orElse("?");
    }

    /** Whether this step carries an action of the given type. */
    public boolean has(Class<? extends Action> type) {
        return actions.stream().anyMatch(type::isInstance);
    }

    /** This step's action of the given type, if it has one. */
    public <T extends Action> Optional<T> find(Class<T> type) {
        return actions.stream().filter(type::isInstance).map(type::cast).findFirst();
    }
}
