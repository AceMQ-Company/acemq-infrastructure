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
package org.acemq.infra.execute.rabbitmq;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.infra.provider.ClusterAccess;
import org.acemq.rabbitmq.admin.AdminException;

/**
 * The four management calls {@code acemq-java-rabbitmq-admin} does not make, and why each one is
 * here rather than there.
 *
 * <p>This is a short class and it exists reluctantly. The precedent is already set one module over:
 * {@code ManagementEndpoint} talks HTTP directly because a probe needs one fact the admin client
 * deliberately does not carry. The same standard applies here — nothing goes in this class because
 * it was quicker than finding the method, and each of the four has a reason.
 *
 * <ul>
 *   <li><strong>A policy with its {@code apply-to} chosen.</strong> {@code RabbitAdmin.putPolicy}
 *       writes {@code "apply-to": "all"}, which is correct for the general case and catastrophic
 *       for the one this module has. A federation policy that applies to <em>all</em> applies to
 *       queues as well as exchanges, and a federated queue pulls from its upstream only when the
 *       upstream has no local consumers — so a mirror declared that way is an accidental drain that
 *       fires the moment a cutover stops the source's consumers. docs/message-state.md calls this
 *       the single easiest thing to get wrong in the domain and it is one JSON field.</li>
 *   <li><strong>An operator policy with its {@code apply-to} preserved.</strong> Same shape, other
 *       direction: {@code putOperatorPolicy} writes {@code "queues"}, so copying a source's
 *       operator policy through it would silently change what the policy applies to.</li>
 *   <li><strong>The publish rate.</strong> It lives in a {@code message_stats} block that nothing
 *       in the admin client models, and a {@code publishRate} guard is unanswerable without it.</li>
 *   <li><strong>The announcement.</strong> There is no publish on a management client, because a
 *       management client is not how anybody should publish. See {@link #publish}.</li>
 * </ul>
 *
 * <p>Everything else goes through {@link org.acemq.rabbitmq.admin.RabbitAdmin}, which knows about
 * the 406 an absent plugin answers with, the vhost encoding, and the fact that a parameter has to
 * be wrapped in a {@code value} object or the broker accepts it and the plugin ignores it.
 */
final class ManagementApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final String authorisation;
    private final java.time.Duration timeout;
    private final HttpClient client;

    ManagementApi(ClusterAccess access) {
        String url = access.management();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.authorisation = "Basic " + Base64.getEncoder().encodeToString(
                (access.username() + ":" + access.password()).getBytes(StandardCharsets.UTF_8));
        this.timeout = access.timeout();
        this.client = HttpClient.newBuilder()
                .connectTimeout(access.timeout())
                // HTTP/1.1, pinned, and this is not a preference. The JDK's client defaults to
                // HTTP/2 and reaches it over cleartext by asking the server to upgrade -- and
                // against RabbitMQ's management listener that handshake fails whenever the request
                // carrying it has a body, with "EOF reached while reading" and nothing on the
                // broker's side to look at. Found by the cutover integration test: the announcement
                // is the first call this class makes in a blue/green run, it is a POST, and it died
                // every time while curl against the identical endpoint answered 200. A GET works,
                // which is why the probe one module over has never seen this, and why it looks like
                // a broken endpoint rather than a broken client -- once any GET has established the
                // connection, the POST that reuses it succeeds, so the failure is the first
                // body-carrying request on a fresh client and nothing else. Every call here writes
                // once, at a known moment, so nothing is being given up by not negotiating.
                .version(HttpClient.Version.HTTP_1_1)
                // A redirect away from the management API means something is in front of the
                // broker, and following it would send these credentials there. The same setting,
                // for the same reason, as the probe's and the admin client's own.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** One path segment, encoded the way the management API wants a vhost encoded. */
    static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Reads a document.
     *
     * @param path the management path, beginning with a slash
     * @return the body as a map, or empty when the broker answered 404 or 406 — which is a fact
     *     about the cluster rather than a failure
     */
    Optional<Map<String, Object>> get(String path) {
        HttpResponse<String> response = send(request(path).GET().build(), "GET " + path);
        if (response.statusCode() == 404 || response.statusCode() == 406) {
            return Optional.empty();
        }
        refuseAnythingBut2xx(response, "GET " + path);
        return Optional.of(read(response.body(), path));
    }

    /**
     * Writes a document.
     *
     * @param path the management path
     * @param body what to send
     */
    void put(String path, Map<String, Object> body) {
        HttpResponse<String> response = send(
                request(path).PUT(HttpRequest.BodyPublishers.ofString(write(body, path))).build(),
                "PUT " + path);
        refuseAnythingBut2xx(response, "PUT " + path);
    }

    /**
     * Publishes one message through the management API.
     *
     * <p>RabbitMQ documents this endpoint as unsuitable for anything but a test, and the warning is
     * about throughput: every message is a separate HTTP request with a separate authentication and
     * a separate channel behind it, so a publisher built on it is slow and unconfirmable. That is a
     * real objection to publishing a workload and not an objection to publishing <em>this</em>: the
     * announcement envelope is one advisory message, once, at a known moment, and the alternative
     * is taking an AMQP client dependency into a module whose entire job is management operations.
     * scripts/blue-green-lab.sh seeds a backlog the same way for the same reason.
     *
     * @param vhost the virtual host
     * @param exchange the exchange to publish to
     * @param routingKey the key
     * @param payload the body, as the file wrote it
     * @return whether the broker routed it to at least one queue
     */
    boolean publish(String vhost, String exchange, String routingKey, String payload) {
        String path = "/api/exchanges/" + encode(vhost) + "/" + encode(exchange) + "/publish";
        Map<String, Object> body = Map.of(
                // Persistent, because an announcement that a broker restart can lose is an
                // announcement whose absence looks exactly like an application that ignored it.
                "properties", Map.of("delivery_mode", 2),
                "routing_key", routingKey,
                "payload", payload,
                "payload_encoding", "string");
        HttpResponse<String> response = send(
                request(path).POST(HttpRequest.BodyPublishers.ofString(write(body, path))).build(),
                "POST " + path);
        refuseAnythingBut2xx(response, "POST " + path);
        return Boolean.TRUE.equals(read(response.body(), path).get("routed"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", authorisation)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest request, String what) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException unreachable) {
            throw new AdminException(what + " failed: " + unreachable.getMessage(), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminException(what + " was interrupted", interrupted);
        }
    }

    private static void refuseAnythingBut2xx(HttpResponse<String> response, String what) {
        if (response.statusCode() / 100 == 2) {
            return;
        }
        // The body is included because the management API's refusals are specific -- which policy,
        // which field -- and a status code on its own sends somebody to the wrong plugin.
        throw new AdminException(what + " answered " + response.statusCode()
                + (response.body() == null || response.body().isBlank()
                        ? "" : ": " + response.body().strip()));
    }

    private static Map<String, Object> read(String body, String path) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(body, Map.class);
            return map;
        } catch (JsonProcessingException unreadable) {
            throw new AdminException("could not read what the broker answered for " + path,
                    unreadable);
        }
    }

    private static String write(Map<String, Object> body, String path) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new AdminException("could not encode the body for " + path, unwritable);
        }
    }
}
