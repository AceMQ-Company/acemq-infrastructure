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
package org.acemq.infra.operator;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.acemq.infra.config.Cluster;
import org.acemq.infra.config.ConfigException;
import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFiles;
import org.acemq.infra.config.Environment;

/**
 * Where a Cutover may send credentials: {@code host[:port]} globs from the operator's own
 * configuration, {@code ACEMQ_INFRA_ALLOWED_URLS}, never from the resource. A Cutover names its
 * clusters' URLs and the operator presents Secrets to them, so without this whoever can create a
 * Cutover can have a namespace's credentials sent anywhere.
 *
 * <p>{@code *} matches any run of characters, dots included, and {@code ?} one. A pattern with no
 * port matches every port; a URL with no port has its scheme's default. Unset means in-cluster
 * Services only.
 */
record Allowlist(List<String> patterns, List<Pattern> compiled) {

    /** The environment variable the operator reads it from. */
    static final String ENV = "ACEMQ_INFRA_ALLOWED_URLS";

    /** In-cluster Services, by their fully qualified names. */
    static final String DEFAULT = "*.svc,*.svc.cluster.local";

    static Allowlist parse(String value) {
        String text = value == null || value.isBlank() ? DEFAULT : value;
        List<String> patterns = Arrays.stream(text.split(",")).map(String::trim)
                .filter(pattern -> !pattern.isEmpty()).toList();
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            String lower = pattern.toLowerCase(Locale.ROOT);
            // host, or host:port; a bare host matches any port.
            int colon = lower.lastIndexOf(':');
            String host = colon < 0 ? lower : lower.substring(0, colon);
            String port = colon < 0 ? "*" : lower.substring(colon + 1);
            compiled.add(Pattern.compile(glob(host) + ":" + glob(port)));
        }
        return new Allowlist(patterns, compiled);
    }

    /** Whether a URL's host and port are allowed. Anything that does not parse is not. */
    boolean allows(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException unparseable) {
            return false;
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            return false;
        }
        int port = uri.getPort() >= 0 ? uri.getPort()
                : switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                    case "http" -> 80;
                    case "https" -> 443;
                    case "amqp" -> 5672;
                    case "amqps" -> 5671;
                    default -> -1;
                };
        String hostPort = uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        return compiled.stream().anyMatch(pattern -> pattern.matcher(hostPort).matches());
    }

    /**
     * Why the deployment file's clusters are not all allowed — every management and AMQP URL, as
     * the variables resolve them — or empty. A file that cannot be read names no URL, and the
     * planner refuses it before anything is dialled.
     */
    Optional<String> refusal(Path file, Map<String, String> env) {
        DeploymentFile read;
        try {
            read = DeploymentFiles.load(file, Environment.of(env));
        } catch (ConfigException unreadable) {
            return Optional.empty();
        }
        for (Cluster cluster : read.clusters().values()) {
            for (Optional<String> url : List.of(cluster.management(), cluster.amqp())) {
                if (url.isPresent() && !allows(url.get())) {
                    return Optional.of("cluster '" + cluster.name() + "' is at "
                            + redacted(url.get()) + ", which is not in the operator's allowlist ("
                            + ENV + "=" + String.join(",", patterns) + "). The operator sends"
                            + " credentials only to allowed hosts; nothing was sent to this one.");
                }
            }
        }
        return Optional.empty();
    }

    private static String redacted(String url) {
        int scheme = url.indexOf("://");
        int at = url.lastIndexOf('@');
        return scheme < 0 || at < scheme ? url : url.substring(0, scheme + 3) + "***@"
                + url.substring(at + 1);
    }

    private static String glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return regex.toString();
    }
}
