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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

import org.acemq.infra.provider.Capability;
import org.acemq.infra.yaml.Location;
import org.acemq.infra.yaml.YamlException;
import org.acemq.infra.yaml.YamlNode;
import org.acemq.infra.yaml.YamlReader;

/**
 * Turns the text of a deployment file into a {@link DeploymentFile}.
 *
 * <p>The parser is strict about shape and permissive about content, and that division is the whole
 * design. A field that must be a mapping and is a list cannot be represented, so it is a
 * {@link ConfigException}; a field that is missing altogether represents perfectly well as an
 * empty {@link Optional}, and what its absence means is the validator's judgement to make. The
 * practical effect is that a reader with four things wrong in their file is told about all four,
 * with lines, in one run.
 *
 * <p>Closed vocabularies — the ones docs/configuration.md writes out as {@code a | b | c} — are
 * resolved here, because {@code operation: rollingUpdate} has no representation in an enum with
 * three constants. Vocabularies the documentation shows one example of and never enumerates
 * ({@code ackMode}, {@code deleteAfter}, a selector's {@code role}) are kept as the text the file
 * wrote. Closing a set the documentation has not closed would mean refusing a spelling the format
 * may well allow, and a parser is a bad place to invent a format.
 */
public final class DeploymentFileParser {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "apiVersion", "kind", "metadata", "clusters", "provider", "providerConfig",
            "endpoint", "deployment", "rollback", "streams");

    private static final Set<String> DEPLOYMENT_KEYS = Set.of(
            "operation", "from", "to", "semantics", "scope", "mirror", "backup", "announce",
            "steps");

    private static final Set<String> SCOPE_KEYS = Set.of("vhost", "queues", "services");

    /**
     * The key each action is written under. Order matters only for reading a step's actions back
     * in the order the file wrote them, which is what makes the "2 actions" error name them the
     * way the reader sees them.
     */
    private static final List<String> ACTION_KEYS = List.of(
            "requires", "copyTopology", "announce", "closeConnections", "drain", "mirror",
            "endpoint");

    private static final Set<String> STEP_KEYS;

    static {
        Set<String> keys = new java.util.HashSet<>(ACTION_KEYS);
        keys.add("id");
        keys.add("waitFor");
        STEP_KEYS = Set.of(keys.toArray(new String[0]));
    }

    private final String origin;
    private final List<ConfigException.Problem> problems = new ArrayList<>();

    private DeploymentFileParser(String origin) {
        this.origin = origin;
    }

    /**
     * @param text the document as written, before interpolation
     * @param origin where it came from, for the messages
     * @param environment where {@code ${VAR}} references are looked up
     * @return the parsed file, whether or not it is a <em>valid</em> deployment
     * @throws ConfigException if the file cannot be represented — bad YAML, a field of the wrong
     *     shape, a word outside a closed vocabulary, or an unset variable
     */
    public static DeploymentFile parse(String text, String origin, Environment environment) {
        String interpolated = Interpolation.apply(text, origin, environment);

        YamlNode root;
        try {
            root = YamlReader.read(interpolated, origin);
        } catch (YamlException error) {
            throw new ConfigException(List.of(
                    new ConfigException.Problem(error.location(), "yaml", strip(error, origin))));
        }
        if (!(root instanceof YamlNode.Mapping document)) {
            throw new ConfigException(List.of(new ConfigException.Problem(root.location(), "yaml",
                    "the document must be a mapping, found " + root.describe())));
        }

        DeploymentFileParser parser = new DeploymentFileParser(origin);
        DeploymentFile parsed = parser.document(document);
        if (!parser.problems.isEmpty()) {
            throw new ConfigException(parser.problems);
        }
        return parsed;
    }

    private static String strip(YamlException error, String origin) {
        // YamlException prefixes its own message with the location, and Problem prefixes it
        // again. One is enough.
        String message = error.getMessage();
        String prefix = error.location().describe() + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    // ---------------------------------------------------------------- document

    private DeploymentFile document(YamlNode.Mapping root) {
        Map<String, Cluster> clusters = new LinkedHashMap<>();
        mapping(root, "clusters", "").ifPresent(block -> {
            for (String name : block.keys()) {
                clusters.put(name, cluster(name, block, "clusters." + name));
            }
        });

        return new DeploymentFile(
                origin,
                string(root, "apiVersion", ""),
                string(root, "kind", ""),
                metadata(root),
                Map.copyOf(clusters),
                string(root, "provider", ""),
                mapping(root, "providerConfig", ""),
                mapping(root, "endpoint", "").map(this::endpoint),
                mapping(root, "deployment", "").map(this::deployment),
                mapping(root, "rollback", "").map(this::rollback),
                mapping(root, "streams", "").map(block -> new Streams(
                        bool(block, "acknowledged", "streams"),
                        restartAt(block),
                        string(block, "note", "streams"),
                        block.location())),
                unknownKeys(root, "", TOP_LEVEL_KEYS),
                root.location());
    }

    /**
     * {@code streams.restartAt}, which is a value or a mapping of stream names to values.
     *
     * <p>The two spellings are a shape decision rather than a content one, so they are settled
     * here: a scalar is one answer for every stream in the scope, a mapping is one answer per
     * stream, and the difference matters to the plan because a mapping can leave a stream out and
     * a scalar cannot. What the answers themselves say is kept as written — docs/message-state.md
     * shows {@code next} and names {@code first}, {@code last}, a timestamp and an absolute offset
     * in prose without ever enumerating them as a closed set, and an absolute offset is a number
     * rather than a word, so there is no vocabulary here to close.
     */
    private RestartAt restartAt(YamlNode.Mapping block) {
        Optional<YamlNode> node = present(block, "restartAt");
        if (node.isEmpty()) {
            return RestartAt.none();
        }
        if (node.get() instanceof YamlNode.Scalar scalar) {
            return RestartAt.everywhere(scalar.text());
        }
        if (!(node.get() instanceof YamlNode.Mapping perStream)) {
            problem(node.get().location(), "streams.restartAt", "expected a restart position or a"
                    + " mapping of stream names to restart positions, found "
                    + node.get().describe());
            return RestartAt.none();
        }
        Map<String, String> byStream = new LinkedHashMap<>();
        for (String stream : perStream.keys()) {
            YamlNode value = perStream.get(stream).orElseThrow();
            if (value instanceof YamlNode.Scalar scalar) {
                byStream.put(stream, scalar.text());
                continue;
            }
            // A named stream with nothing against it is not the same as a stream the file never
            // named: it is a line somebody meant to finish, and reading it as absent would send
            // the reader to the plan's refusal about coverage instead of to the line itself.
            problem(value.location(), "streams.restartAt." + stream,
                    "expected a restart position, found " + value.describe());
        }
        return RestartAt.perStream(byStream);
    }

    private DeploymentFile.Metadata metadata(YamlNode.Mapping root) {
        Optional<YamlNode.Mapping> block = mapping(root, "metadata", "");
        return new DeploymentFile.Metadata(
                block.flatMap(one -> string(one, "name", "metadata")),
                block.<Location>map(YamlNode.Mapping::location).orElse(root.location()));
    }

    private Cluster cluster(String name, YamlNode.Mapping clusters, String path) {
        YamlNode.Mapping block = require(clusters, name, path);
        return new Cluster(
                name,
                string(block, "management", path),
                string(block, "amqp", path),
                string(block, "vhost", path),
                string(block, "username", path),
                string(block, "password", path),
                mapping(block, "tls", path).map(tls -> new Cluster.Tls(
                        bool(tls, "verify", path + ".tls"),
                        string(tls, "caFile", path + ".tls"),
                        tls.location())),
                clusters.keyLocation(name));
    }

    private Endpoint endpoint(YamlNode.Mapping block) {
        return new Endpoint(
                constant(block, "kind", "endpoint", EndpointKind::of, EndpointKind.names(),
                        "an endpoint kind"),
                string(block, "description", "endpoint"),
                string(block, "run", "endpoint"),
                strings(block, "args", "endpoint"),
                duration(block, "timeout", "endpoint"),
                block.location());
    }

    // -------------------------------------------------------------- deployment

    private Deployment deployment(YamlNode.Mapping block) {
        return new Deployment(
                constant(block, "operation", "deployment", Operation::of, Operation.names(),
                        "an operation"),
                string(block, "from", "deployment"),
                string(block, "to", "deployment"),
                constant(block, "semantics", "deployment", Semantics::of, Semantics.names(),
                        "a delivery guarantee"),
                mapping(block, "scope", "deployment").map(scope -> new Deployment.Scope(
                        string(scope, "vhost", "deployment.scope"),
                        strings(scope, "queues", "deployment.scope"),
                        strings(scope, "services", "deployment.scope"),
                        unknownKeys(scope, "deployment.scope", SCOPE_KEYS),
                        scope.location())),
                mapping(block, "mirror", "deployment").map(one -> mirrorSpec(one, "deployment.mirror")),
                mapping(block, "backup", "deployment").map(backup -> new Deployment.Backup(
                        bool(backup, "enabled", "deployment.backup"),
                        string(backup, "path", "deployment.backup"),
                        bool(backup, "redactCredentials", "deployment.backup"),
                        backup.location())),
                mapping(block, "announce", "deployment").map(announce -> new Deployment.Announce(
                        string(announce, "exchange", "deployment.announce"),
                        string(announce, "routingKey", "deployment.announce"),
                        string(announce, "payload", "deployment.announce"),
                        announce.location())),
                steps(block, "deployment.steps"),
                unknownKeys(block, "deployment", DEPLOYMENT_KEYS),
                block.location());
    }

    private Rollback rollback(YamlNode.Mapping block) {
        return new Rollback(
                string(block, "keep", "rollback"),
                // `for` in the file. There is no accessor spelling of it in Java, so the record
                // component is keepFor and this is the only place the two names meet.
                duration(block, "for", "rollback"),
                steps(block, "rollback.steps"),
                block.location());
    }

    private MirrorSpec mirrorSpec(YamlNode.Mapping block, String path) {
        return new MirrorSpec(
                strings(block, "exchanges", path),
                strings(block, "queues", path),
                mapping(block, "upstream", path).map(upstream -> new MirrorSpec.Upstream(
                        string(upstream, "uri", path + ".upstream"),
                        integer(upstream, "prefetch", path + ".upstream"),
                        string(upstream, "ackMode", path + ".upstream"),
                        upstream.location())),
                block.location());
    }

    // ------------------------------------------------------------------- steps

    private Optional<List<Step>> steps(YamlNode.Mapping block, String path) {
        Optional<YamlNode> node = present(block, "steps");
        if (node.isEmpty()) {
            // Absent, which is not the same as empty: the default list for the operation gets
            // filled in and printed. An empty list is a file that asked for no steps at all, and
            // the validator refuses that.
            return Optional.empty();
        }
        if (!(node.get() instanceof YamlNode.Sequence sequence)) {
            problem(node.get().location(), path,
                    "expected a list of steps, found " + node.get().describe());
            return Optional.empty();
        }
        List<Step> steps = new ArrayList<>(sequence.items().size());
        for (int index = 0; index < sequence.items().size(); index++) {
            steps.add(step(sequence.items().get(index), path + "[" + index + "]"));
        }
        return Optional.of(List.copyOf(steps));
    }

    private Step step(YamlNode node, String path) {
        if (!(node instanceof YamlNode.Mapping block)) {
            problem(node.location(), path, "expected a step, found " + node.describe());
            return new Step(Optional.empty(), List.of(), Optional.empty(), List.of(),
                    node.location());
        }
        Optional<String> id = string(block, "id", path);
        String where = id.map(name -> path + "(" + name + ")").orElse(path);

        List<Action> actions = new ArrayList<>();
        for (String key : block.keys()) {
            if (ACTION_KEYS.contains(key)) {
                action(key, block, where).ifPresent(actions::add);
            }
        }

        return new Step(
                id,
                List.copyOf(actions),
                mapping(block, "waitFor", where).map(guard -> waitFor(guard, where + ".waitFor")),
                unknownKeys(block, where, STEP_KEYS),
                block.location());
    }

    private Optional<Action> action(String key, YamlNode.Mapping step, String where) {
        Location at = step.keyLocation(key);
        if ("requires".equals(key)) {
            return Optional.of(requires(step, where, at));
        }
        YamlNode.Mapping body = require(step, key, where + "." + key);
        return Optional.of(switch (key) {
            case "copyTopology" -> new Action.CopyTopology(
                    string(body, "from", where + ".copyTopology"),
                    string(body, "to", where + ".copyTopology"),
                    strings(body, "vhosts", where + ".copyTopology"),
                    constants(body, "include", where + ".copyTopology", TopologyPart::of,
                            TopologyPart.names(), "a topology part"),
                    constants(body, "exclude", where + ".copyTopology", TopologyPart::of,
                            TopologyPart.names(), "a topology part"),
                    at);
            case "announce" -> new Action.Announce(at);
            case "closeConnections" -> new Action.CloseConnections(
                    string(body, "on", where + ".closeConnections"),
                    mapping(body, "select", where + ".closeConnections")
                            .map(select -> new Action.CloseConnections.Selector(
                            strings(select, "users", where + ".closeConnections.select"),
                            string(select, "role", where + ".closeConnections.select"),
                            select.location())),
                    mapping(body, "after", where + ".closeConnections")
                            .map(after -> new Action.CloseConnections.After(
                            integer(after, "unacked", where + ".closeConnections.after"),
                            duration(after, "timeout", where + ".closeConnections.after"),
                            constant(after, "onTimeout", where + ".closeConnections.after",
                                    OnTimeout::of, OnTimeout.names(), "a timeout policy"),
                            after.location())),
                    at);
            case "drain" -> new Action.Drain(
                    string(body, "from", where + ".drain"),
                    string(body, "to", where + ".drain"),
                    strings(body, "queues", where + ".drain"),
                    string(body, "ackMode", where + ".drain"),
                    string(body, "deleteAfter", where + ".drain"),
                    at);
            case "mirror" -> new Action.Mirror(
                    string(body, "from", where + ".mirror"),
                    string(body, "to", where + ".mirror"),
                    strings(body, "exchanges", where + ".mirror"),
                    strings(body, "queues", where + ".mirror"),
                    at);
            case "endpoint" -> new Action.Switch(string(body, "target", where + ".endpoint"), at);
            default -> throw new IllegalStateException("unreachable action key " + key);
        });
    }

    private Action.Requires requires(YamlNode.Mapping step, String where, Location at) {
        YamlNode node = step.get("requires").orElseThrow();
        if (node instanceof YamlNode.Null) {
            return new Action.Requires(List.of(), at);
        }
        if (!(node instanceof YamlNode.Sequence sequence)) {
            problem(node.location(), where + ".requires",
                    "expected a list of capability names, found " + node.describe());
            return new Action.Requires(List.of(), at);
        }
        List<Capability> capabilities = new ArrayList<>();
        for (YamlNode item : sequence.items()) {
            if (!(item instanceof YamlNode.Scalar scalar)) {
                problem(item.location(), where + ".requires",
                        "expected a capability name, found " + item.describe());
                continue;
            }
            Optional<Capability> capability = Capability.of(scalar.text());
            if (capability.isEmpty()) {
                problem(item.location(), where + ".requires", "'" + scalar.text()
                        + "' is not a capability; expected one of [" + Capability.names() + "]"
                        + " (docs/broker-agnostic.md)");
                continue;
            }
            capabilities.add(capability.get());
        }
        return new Action.Requires(List.copyOf(capabilities), at);
    }

    private WaitFor waitFor(YamlNode.Mapping block, String path) {
        return new WaitFor(
                string(block, "on", path),
                integer(block, "publishRate", path),
                integer(block, "unacked", path),
                integer(block, "depth", path),
                mapping(block, "consumers", path).map(consumers -> new WaitFor.Consumers(
                        integer(consumers, "min", path + ".consumers"),
                        integer(consumers, "max", path + ".consumers"),
                        consumers.location())),
                duration(block, "timeout", path),
                constant(block, "onTimeout", path, OnTimeout::of, OnTimeout.names(),
                        "a timeout policy"),
                block.location());
    }

    // --------------------------------------------------------------- accessors

    /** The value at {@code key}, treating an explicit YAML null as absence. */
    private static Optional<YamlNode> present(YamlNode.Mapping block, String key) {
        return block.get(key).filter(node -> !(node instanceof YamlNode.Null));
    }

    private Optional<YamlNode.Mapping> mapping(YamlNode.Mapping block, String key, String path) {
        Optional<YamlNode> node = present(block, key);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        if (node.get() instanceof YamlNode.Mapping found) {
            return Optional.of(found);
        }
        problem(node.get().location(), dotted(path, key),
                "expected a mapping, found " + node.get().describe());
        return Optional.empty();
    }

    /** Like {@link #mapping} but for a key already known to be there, used where a null body is fine. */
    private YamlNode.Mapping require(YamlNode.Mapping block, String key, String path) {
        YamlNode node = block.get(key).orElseThrow();
        if (node instanceof YamlNode.Mapping found) {
            return found;
        }
        if (!(node instanceof YamlNode.Null)) {
            problem(node.location(), path, "expected a mapping, found " + node.describe());
        }
        return YamlNode.Mapping.empty(node.location());
    }

    private Optional<String> string(YamlNode.Mapping block, String key, String path) {
        Optional<YamlNode> node = present(block, key);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        if (node.get() instanceof YamlNode.Scalar scalar) {
            return Optional.of(scalar.text());
        }
        problem(node.get().location(), dotted(path, key),
                "expected a value, found " + node.get().describe());
        return Optional.empty();
    }

    private Optional<Boolean> bool(YamlNode.Mapping block, String key, String path) {
        Optional<YamlNode> node = present(block, key);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        if (node.get() instanceof YamlNode.Scalar scalar && scalar.isBoolean()) {
            return Optional.of(Boolean.parseBoolean(scalar.text()));
        }
        problem(node.get().location(), dotted(path, key), "expected true or false, found "
                + describeValue(node.get()));
        return Optional.empty();
    }

    private OptionalInt integer(YamlNode.Mapping block, String key, String path) {
        Optional<YamlNode> node = present(block, key);
        if (node.isEmpty()) {
            return OptionalInt.empty();
        }
        if (node.get() instanceof YamlNode.Scalar scalar && scalar.isInteger()) {
            try {
                return OptionalInt.of(Integer.parseInt(scalar.text()));
            } catch (NumberFormatException ignored) {
                // A YAML int that will not fit in a Java int. Everything this field holds is a
                // queue depth or a prefetch, so the number is wrong rather than the type.
            }
        }
        problem(node.get().location(), dotted(path, key), "expected a whole number, found "
                + describeValue(node.get()));
        return OptionalInt.empty();
    }

    private List<String> strings(YamlNode.Mapping block, String key, String path) {
        Optional<YamlNode> node = present(block, key);
        if (node.isEmpty()) {
            return List.of();
        }
        if (!(node.get() instanceof YamlNode.Sequence sequence)) {
            problem(node.get().location(), dotted(path, key),
                    "expected a list, found " + node.get().describe());
            return List.of();
        }
        List<String> values = new ArrayList<>(sequence.items().size());
        for (YamlNode item : sequence.items()) {
            if (item instanceof YamlNode.Scalar scalar) {
                values.add(scalar.text());
            } else {
                problem(item.location(), dotted(path, key),
                        "expected a value, found " + item.describe());
            }
        }
        return List.copyOf(values);
    }

    private <E> Optional<E> constant(YamlNode.Mapping block, String key, String path,
                                     Function<String, Optional<E>> resolve, String names,
                                     String what) {
        Optional<String> text = string(block, key, path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Optional<E> resolved = resolve.apply(text.get());
        if (resolved.isEmpty()) {
            problem(block.keyLocation(key), dotted(path, key),
                    "'" + text.get() + "' is not " + what + "; expected one of [" + names + "]");
        }
        return resolved;
    }

    private <E> List<E> constants(YamlNode.Mapping block, String key, String path,
                                  Function<String, Optional<E>> resolve, String names,
                                  String what) {
        List<E> values = new ArrayList<>();
        for (String text : strings(block, key, path)) {
            Optional<E> resolved = resolve.apply(text);
            if (resolved.isEmpty()) {
                problem(block.keyLocation(key), dotted(path, key),
                        "'" + text + "' is not " + what + "; expected one of [" + names + "]");
                continue;
            }
            values.add(resolved.get());
        }
        return List.copyOf(values);
    }

    private Optional<Duration> duration(YamlNode.Mapping block, String key, String path) {
        Optional<String> text = string(block, key, path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Optional<Duration> parsed = Durations.parse(text.get());
        if (parsed.isEmpty()) {
            problem(block.keyLocation(key), dotted(path, key), "'" + text.get()
                    + "' is not a duration; expected a number and a unit, such as 15m, 90s or 72h");
        }
        return parsed;
    }

    private List<UnknownKey> unknownKeys(YamlNode.Mapping block, String path, Set<String> known) {
        List<UnknownKey> unknown = new ArrayList<>();
        for (String key : block.keys()) {
            if (!known.contains(key)) {
                unknown.add(new UnknownKey(path, key, block.keyLocation(key)));
            }
        }
        return List.copyOf(unknown);
    }

    private static String dotted(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /** A scalar quoted as written, or "a mapping"/"a sequence", for a type mismatch message. */
    private static String describeValue(YamlNode node) {
        return node instanceof YamlNode.Scalar scalar ? "'" + scalar.text() + "'" : node.describe();
    }

    private void problem(Location location, String path, String message) {
        problems.add(new ConfigException.Problem(location, path, message));
    }
}
