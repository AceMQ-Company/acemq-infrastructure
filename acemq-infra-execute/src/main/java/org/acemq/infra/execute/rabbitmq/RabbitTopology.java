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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.infra.config.TopologyPart;
import org.acemq.infra.execute.Topology;
import org.acemq.rabbitmq.admin.AdminException;
import org.acemq.rabbitmq.admin.PolicyInfo;

/**
 * A RabbitMQ definitions document, narrowed to one virtual host and splittable by part.
 *
 * <p>The splitting is what the whole thing is for, and docs/message-state.md calls it the single
 * most valuable line in the default step list. A definitions import applies its policies the
 * instant it lands, so a {@code message-ttl} or a {@code max-length} that arrives with the shape is
 * live on an empty cluster long before the drain starts filling it — and a forty-minute backlog
 * shovelled into a queue enforcing a ten-minute TTL is a forty-minute backlog discarded on arrival.
 * So the copy goes in two halves with the drain between them, and this class is what makes a half
 * expressible: {@link #documentFor(List)} emits the document with only the named sections in it.
 *
 * <p>Two things RabbitMQ's own document does not do, found by trying to write this rather than by
 * reading about it.
 *
 * <ul>
 *   <li><strong>Operator policies are not in it.</strong> {@code /api/definitions} carries
 *       {@code policies} and nothing else of that kind; operator policies live behind
 *       {@code /api/operator-policies} and have to be copied one at a time. They are carried beside
 *       the document here for that reason.</li>
 *   <li><strong>The vhost-scoped export cannot be imported.</strong>
 *       {@code /api/definitions/&lt;vhost&gt;} leaves the {@code vhost} field off every entry, and
 *       the only import endpoint is the cluster-wide one — so the scoped document would land in the
 *       default virtual host. The cluster-wide export is taken and narrowed here instead.</li>
 * </ul>
 */
final class RabbitTopology implements Topology {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The sections of a definitions document whose entries are scoped to a virtual host. */
    private static final Set<String> PER_VHOST =
            Set.of("queues", "exchanges", "bindings", "policies", "parameters", "permissions",
                    "topic_permissions");

    /** The field in a user entry that makes the whole document a credential. */
    private static final String PASSWORD_HASH = "password_hash";

    private final String from;
    private final String vhost;
    private final Map<String, Object> document;
    private final List<PolicyInfo> operatorPolicies;

    private RabbitTopology(String from, String vhost, Map<String, Object> document,
                           List<PolicyInfo> operatorPolicies) {
        this.from = from;
        this.vhost = vhost;
        this.document = document;
        this.operatorPolicies = List.copyOf(operatorPolicies);
    }

    /**
     * Narrows a cluster-wide definitions document to one virtual host.
     *
     * @param from the name the deployment file gave the cluster this was read from
     * @param vhost the virtual host to keep
     * @param whole the document as the broker sent it
     * @param operatorPolicies the operator policies, which the document does not carry
     * @return the narrowed topology
     */
    static RabbitTopology of(String from, String vhost, Map<String, Object> whole,
                             List<PolicyInfo> operatorPolicies) {
        Map<String, Object> narrowed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> section : whole.entrySet()) {
            if (!PER_VHOST.contains(section.getKey())) {
                narrowed.put(section.getKey(), section.getValue());
                continue;
            }
            narrowed.put(section.getKey(), entries(section.getValue()).stream()
                    .filter(entry -> vhost.equals(entry.get("vhost"))).toList());
        }
        // The vhosts section is left whole on purpose. A cutover between clusters needs the target
        // to have the virtual host before anything can be declared in it, and a definitions import
        // creates one that is missing -- which is the difference between a copy that works on a
        // fresh cluster and one that needs a human to have run rabbitmqctl first.
        return new RabbitTopology(from, vhost, narrowed, operatorPolicies);
    }

    @Override
    public String from() {
        return from;
    }

    @Override
    public String describe() {
        List<String> counted = new ArrayList<>();
        for (TopologyPart part : TopologyPart.values()) {
            int many = count(part);
            if (many > 0) {
                counted.add(many + " " + part.wire());
            }
        }
        return counted.isEmpty() ? "nothing in scope" : String.join(", ", counted);
    }

    @Override
    public long writeTo(Path path, boolean redactCredentials) throws IOException {
        String json = write(redactCredentials ? redacted() : document);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        if (!redactCredentials) {
            // Written unredacted on purpose, so the file now carries password hashes, and a hash is
            // enough to stand up a broker the real passwords authenticate against. 0600 is the
            // least this can do about that, and the path goes in the report so nobody loses it.
            try {
                Files.setPosixFilePermissions(path,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException notPosix) {
                // A filesystem with no POSIX permissions, which is a fact about where this is
                // running rather than a failure of the backup. Saying nothing would be worse than
                // saying it, and the caller has nowhere to put a warning, so it goes in the file's
                // own neighbourhood: the report already names the path and the redaction setting.
                Files.write(path.resolveSibling(path.getFileName() + ".UNREDACTED"),
                        ("this backup carries password hashes and this filesystem could not be"
                                + " asked for 0600. Treat it as a credential.\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
        }
        return Files.size(path);
    }

    /**
     * The document with only the named sections in it, ready to be imported.
     *
     * @param parts the parts this half of the copy applies
     * @return the JSON to post
     */
    String documentFor(List<TopologyPart> parts) {
        Map<String, Object> half = new LinkedHashMap<>();
        // Kept because the broker reads it to decide what it is looking at, and an import without
        // it is refused on some versions.
        Optional.ofNullable(document.get("rabbit_version"))
                .ifPresent(version -> half.put("rabbit_version", version));
        for (TopologyPart part : parts) {
            section(part).ifPresent(key ->
                    half.put(key, document.getOrDefault(key, List.of())));
        }
        return write(half);
    }

    /** The operator policies, which have to be applied one at a time. */
    List<PolicyInfo> operatorPolicies() {
        return operatorPolicies;
    }

    /** How many of one part there are in scope. */
    int count(TopologyPart part) {
        if (part == TopologyPart.OPERATOR_POLICIES) {
            return operatorPolicies.size();
        }
        return section(part).map(key -> entries(document.get(key)).size()).orElse(0);
    }

    /**
     * The definitions document's name for a part.
     *
     * <p>Empty for {@link TopologyPart#OPERATOR_POLICIES}, which is not a section of the document at
     * all — the one place where the configuration format's vocabulary and RabbitMQ's do not line
     * up, and the reason {@link #operatorPolicies()} exists beside this.
     */
    private static Optional<String> section(TopologyPart part) {
        return switch (part) {
            case EXCHANGES -> Optional.of("exchanges");
            case QUEUES -> Optional.of("queues");
            case BINDINGS -> Optional.of("bindings");
            case USERS -> Optional.of("users");
            case PERMISSIONS -> Optional.of("permissions");
            case PARAMETERS -> Optional.of("parameters");
            case POLICIES -> Optional.of("policies");
            case VHOSTS -> Optional.of("vhosts");
            case OPERATOR_POLICIES -> Optional.empty();
        };
    }

    /**
     * The document with the password data taken out of the users.
     *
     * <p>Which makes the backup insufficient to restore a cluster on its own, and that is the trade
     * docs/message-state.md states rather than leaves to be discovered during a restore. The
     * accounts come back; the passwords do not.
     */
    private Map<String, Object> redacted() {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> user : entries(document.get("users"))) {
            Map<String, Object> without = new LinkedHashMap<>(user);
            without.remove(PASSWORD_HASH);
            without.remove("hashing_algorithm");
            users.add(without);
        }
        copy.put("users", users);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Object section) {
        if (!(section instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                entries.add((Map<String, Object>) map);
            }
        }
        return entries;
    }

    private static String write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new AdminException("could not write the definitions document out", unwritable);
        }
    }

    @Override
    public String toString() {
        return "topology of " + from + " " + vhost + ": " + describe();
    }
}
