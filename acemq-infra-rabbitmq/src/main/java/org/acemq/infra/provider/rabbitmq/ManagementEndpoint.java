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
package org.acemq.infra.provider.rabbitmq;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.acemq.infra.provider.ClusterAccess;

/**
 * Whether an endpoint exists, which is how a plugin is detected.
 *
 * <p>This is the one thing the probe cannot ask {@link org.acemq.rabbitmq.admin.RabbitAdmin} for,
 * and the reason is a deliberate decision in that library rather than an oversight. The management
 * API answers <strong>406</strong> for an endpoint whose plugin is not enabled — {@code
 * /api/shovels} without {@code rabbitmq_shovel_management}, for instance — and the admin client
 * maps that onto an empty list, so that "are there any shovels?" is answerable on the many brokers
 * where the answer is simply none. That is right for a client and wrong for a probe: a cluster
 * with the plugin enabled and no shovels declared, and a cluster without the plugin, produce the
 * same empty list, and the whole value of the capability model is telling those two apart before
 * step six rather than during it.
 *
 * <p>So the probe asks the endpoint itself and reads the status code. Nothing else here talks HTTP
 * directly, and nothing else should: this is thirty lines because it needs one fact the layer
 * above deliberately does not carry, not because the layer above is insufficient.
 *
 * <p>It is a GET, with no body, and it is the only request in this module that is not made through
 * the read-only client. It is still a read.
 */
final class ManagementEndpoint {

    /** What the broker said about an endpoint. */
    enum Presence {

        /** The endpoint answered, so its plugin is enabled. */
        PRESENT,

        /** 404 or 406: the plugin providing it is not enabled. A fact, not a failure. */
        ABSENT,

        /** 401 or 403: it may well be there, and these credentials cannot see it. */
        FORBIDDEN,

        /** Nothing answered at all. */
        UNREACHABLE
    }

    private ManagementEndpoint() {
    }

    /**
     * Asks whether an endpoint is there.
     *
     * @param access the cluster and the credentials
     * @param path the management path, beginning with a slash
     * @return what the broker said
     */
    static Presence check(ClusterAccess access, String path) {
        String base = access.management().endsWith("/")
                ? access.management().substring(0, access.management().length() - 1)
                : access.management();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(access.timeout())
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (access.username() + ":" + access.password())
                                .getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<Void> response;
        try {
            response = client(access).send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException error) {
            return Presence.UNREACHABLE;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Presence.UNREACHABLE;
        }

        switch (response.statusCode()) {
            case 200:
                return Presence.PRESENT;
            case 404:
            case 406:
                return Presence.ABSENT;
            case 401:
            case 403:
                return Presence.FORBIDDEN;
            default:
                // Anything else is a broker saying something this has no reading for, and
                // guessing at it would put a capability in a plan on the strength of a 502 from
                // a load balancer.
                return Presence.UNREACHABLE;
        }
    }

    private static HttpClient client(ClusterAccess access) {
        return HttpClient.newBuilder()
                .connectTimeout(access.timeout())
                // A redirect away from the management API means something is in front of the
                // broker -- a proxy, a login page -- and following it would send these
                // credentials there. The same reasoning, and the same setting, as the admin
                // client's own.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
