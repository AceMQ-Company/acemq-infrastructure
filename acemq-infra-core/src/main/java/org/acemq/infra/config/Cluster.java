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

import java.util.Optional;

import org.acemq.infra.yaml.Location;

/**
 * One entry in the {@code clusters:} map.
 *
 * <p>The names are keys and nothing more. {@code blue} and {@code green} are what the worked
 * examples happen to use; {@code old}/{@code new} and {@code dc1}/{@code dc2} work identically,
 * and everything else in the file refers to a cluster by its key. One place for credentials was
 * the best idea in the format this inherits and it is kept unchanged.
 *
 * <p>Every field is optional here because absence is the validator's business, not the parser's —
 * a file missing a password still parses, and the reader gets told which cluster and which field
 * rather than a stack trace.
 *
 * @param name the key this cluster was written under
 * @param management the management API base URL
 * @param amqp the AMQP URI. Needed by drain and mirror, which run broker-side: a shovel dials this
 *     URI from inside the broker, so it must be an address the <em>broker</em> can reach rather
 *     than the one the operator's laptop uses
 * @param vhost the vhost this deployment concerns
 * @param username the management user
 * @param password that user's password, which in a reviewable file means a {@code ${VAR}}
 * @param tls how to verify the management endpoint
 * @param location where this entry is written
 */
public record Cluster(String name, Optional<String> management, Optional<String> amqp,
                      Optional<String> vhost, Optional<String> username, Optional<String> password,
                      Optional<Tls> tls, Location location) {

    /**
     * @param verify whether to verify the management endpoint's certificate
     * @param caFile a CA bundle to verify against
     * @param location where the {@code tls:} block is written
     */
    public record Tls(Optional<Boolean> verify, Optional<String> caFile, Location location) {
    }
}
