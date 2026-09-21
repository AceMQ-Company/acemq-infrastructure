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
package org.acemq.infra.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a provider needs in order to reach one cluster, and nothing else.
 *
 * <p>This exists rather than handing a provider the {@code Cluster} record out of the
 * configuration model, because the dependency in docs/library.md runs one way: this package must
 * not know that a deployment file exists. The cost is a five-field translation in the caller. What
 * it buys is that a second provider — or a test, or an operator reading a Kubernetes secret — can
 * produce one of these without a YAML document anywhere in the picture.
 *
 * <p>The {@code amqp:} URL is deliberately absent. A probe reads the management API and nothing
 * else; a provider that needed an AMQP connection to answer "what can this cluster do" would be
 * opening a client connection during a command whose whole promise is that it changes nothing.
 *
 * @param name the name the deployment file gave this cluster: {@code blue}, {@code dc1}, whatever
 *     the file's author chose. It is carried through into the plan output
 * @param management the management API's base URL
 * @param vhost the virtual host in scope, which bounds almost everything a probe counts
 * @param username the management user, which is a broker user and not an AMQP one
 * @param password its password
 * @param timeout how long any single management call may take
 */
public record ClusterAccess(String name, String management, String vhost, String username,
                            String password, Duration timeout) {

    /** What a management call gets before it is called unreachable. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public ClusterAccess {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(management, "management");
        Objects.requireNonNull(vhost, "vhost");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * The usual case: a cluster reached with the default timeout.
     *
     * @param name the deployment file's name for the cluster
     * @param management the management API's base URL
     * @param vhost the virtual host in scope
     * @param username the management user
     * @param password its password
     * @return the access details
     */
    public static ClusterAccess to(String name, String management, String vhost, String username,
                                   String password) {
        return new ClusterAccess(name, management, vhost, username, password, DEFAULT_TIMEOUT);
    }

    /**
     * The URL with any credentials in it removed.
     *
     * <p>A management URL is allowed to carry {@code user:password@} and an operator who wrote one
     * that way has put a password somewhere every error message would otherwise repeat. Plan
     * artifacts go into pull requests — docs/message-state.md — so the redaction happens at the
     * value rather than at each of the places that print it.
     *
     * @return the URL, with any userinfo replaced
     */
    public String redactedManagement() {
        int scheme = management.indexOf("://");
        int at = management.indexOf('@', scheme < 0 ? 0 : scheme + 3);
        if (scheme < 0 || at < 0) {
            return management;
        }
        return management.substring(0, scheme + 3) + "***@" + management.substring(at + 1);
    }

    /** The vhost as a human reads it, since {@code /} is easy to miss in a sentence. */
    public Optional<String> describedVhost() {
        return vhost.isEmpty() ? Optional.empty() : Optional.of(vhost);
    }
}
