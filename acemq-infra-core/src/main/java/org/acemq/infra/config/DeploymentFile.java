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
import java.util.Map;
import java.util.Optional;

import org.acemq.infra.yaml.Location;
import org.acemq.infra.yaml.YamlNode;

/**
 * A deployment file, parsed.
 *
 * <p>The file is the interface — everything else, the CLI and an eventual operator included, is a
 * way of reading it. That is the jreleaser lesson applied, and the consequence is that this record
 * has to be able to represent a file that is <em>wrong</em>, not only one that is right. Almost
 * every field is optional, because a reader who left out {@code metadata.name} deserves to be told
 * which field and which line rather than to be handed a parse failure about a missing key.
 *
 * <p>What cannot be represented is refused by the parser as a {@link ConfigException}; everything
 * else is somebody else's judgement, and {@code org.acemq.infra.validate} makes it.
 *
 * @param origin where the document came from: a path, or a name for a document held in memory
 * @param apiVersion kept as written so the validator can say what it expected and what it found
 * @param kind likewise
 * @param metadata the deployment's name
 * @param clusters the named clusters, in file order
 * @param provider the provider's short name, resolved through the provider registry rather than
 *     being a closed vocabulary here
 * @param providerConfig provider-specific settings, left as a node tree because only the provider
 *     knows their shape
 * @param endpoint how clients find the live cluster
 * @param deployment what is being done
 * @param rollback how it is undone
 * @param streams the stream-offset acknowledgement
 * @param unknownKeys top-level keys the model has no field for
 * @param location the start of the document
 */
public record DeploymentFile(String origin, Optional<String> apiVersion, Optional<String> kind,
                             Metadata metadata, Map<String, Cluster> clusters,
                             Optional<String> provider, Optional<YamlNode.Mapping> providerConfig,
                             Optional<Endpoint> endpoint, Optional<Deployment> deployment,
                             Optional<Rollback> rollback, Optional<Streams> streams,
                             List<UnknownKey> unknownKeys, Location location) {

    /** The apiVersion every deployment file must declare. */
    public static final String API_VERSION = "acemq.org/v1alpha1";

    /** The kind every deployment file must declare. */
    public static final String KIND = "Deployment";

    /**
     * The one-line summary the linter prints on a file that passes, and that the plan header
     * repeats.
     *
     * <p>Counts the steps the file actually wrote. A file that left {@code steps:} out reports
     * zero, which is honest: the default list is filled in by the planner, against probed
     * clusters, and this record has not met one.
     */
    public String summary() {
        String operation = deployment.flatMap(Deployment::operation).map(Operation::wire).orElse("?");
        int steps = deployment.map(one -> one.stepsOrEmpty().size()).orElse(0);
        return operation + ", " + steps + " steps, " + clusters.size() + " clusters";
    }

    /**
     * The deployment's name, which appears in the plan, the backup filename, the announcement and
     * every log line.
     *
     * @param name the name, absent when the file did not give one
     * @param location where {@code metadata:} is written
     */
    public record Metadata(Optional<String> name, Location location) {
    }
}
