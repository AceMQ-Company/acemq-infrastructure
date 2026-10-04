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
package org.acemq.infra.execute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.acemq.infra.config.Step;

/**
 * What a cutover actually did, on disk, so that it can be undone by a process that did not run it.
 *
 * <p>{@link Execution#rollback()} is derived from the steps that reached {@code done}, and that is
 * only reachable from the JVM that ran the cutover. A binary run from a pipeline exits, and the
 * rollback has to be asked for later, by somebody else, from somewhere else. So {@code apply}
 * writes this file and {@code rollback} reads it.
 *
 * <p>It is rewritten after every step, through a temporary file and an atomic rename, so a process
 * killed between two steps leaves the journal as it was after the last one and never a torn one. A
 * step is also recorded as {@code started} before it does anything: a journal still saying
 * {@code running} with a step left at {@code started} is the record of a crash in the middle of
 * that step, and the rollback treats that step as having happened — a drain killed halfway has
 * moved some of the messages, and the drain-back moves whatever it moved.
 *
 * <p>Tree model only, written and read by hand. Nothing here is bound into a class by reflection,
 * so the native image needs no registration for it. The format is in docs/blue-green.md.
 */
public final class Journal {

    /**
     * The format this build writes and the newest it reads. A journal with a larger number was
     * written by a newer tool, and reading it as this one understands it would be guessing.
     */
    public static final int FORMAT = 1;

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path path;
    private final ObjectNode root;

    /** Where steps are written: the root for a cutover, the {@code rollback} block for its undo. */
    private final ObjectNode section;

    /** Why a write after the first one failed, when one did. */
    private String broken;

    private Journal(Path path, ObjectNode root, ObjectNode section) {
        this.path = path;
        this.root = root;
        this.section = section;
    }

    /**
     * Starts the journal for a cutover about to run.
     *
     * <p>Refuses to replace a file that is already there. An existing journal is the only record
     * of how to undo some earlier run, and overwriting it is the one mistake this file exists to
     * prevent.
     *
     * @param path where to write it
     * @param tool the tool and its version, for whoever reads the file
     * @param deployment the deployment's name
     * @param file the deployment file, as it will be given back to {@code rollback}
     * @param fileBytes the deployment file's bytes, before interpolation, for the fingerprint
     * @param from the cluster being moved away from
     * @param to the cluster being moved to
     * @param clusters every cluster's management URL by name, to notice a journal from elsewhere
     * @return the journal, already on disk
     * @throws UncheckedIOException when it cannot be written, which must stop the run
     * @throws IllegalStateException when something is already at {@code path}
     */
    public static Journal begin(Path path, String tool, String deployment, Path file,
                                byte[] fileBytes, String from, String to,
                                Map<String, String> clusters) {
        if (Files.exists(path)) {
            throw new IllegalStateException(path + " already exists. It is the journal of an"
                    + " earlier run and the only record of how to undo it, so it is not"
                    + " overwritten: pass --journal with a new path, or move that one aside.");
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("tool", tool);
        root.put("deployment", deployment);
        root.put("file", file.toAbsolutePath().normalize().toString());
        root.put("fileSha256", sha256(fileBytes));
        root.put("from", from);
        root.put("to", to);
        ObjectNode urls = root.putObject("clusters");
        clusters.forEach(urls::put);
        root.put("startedAt", Instant.now().toString());
        root.put("outcome", "running");
        root.putArray("steps");
        root.putArray("movements");
        Journal journal = new Journal(path, root, root);
        try {
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            journal.write();
        } catch (IOException failed) {
            throw new UncheckedIOException("the journal could not be written to " + path + ": "
                    + failed.getMessage(), failed);
        }
        return journal;
    }

    /**
     * Reads a journal back.
     *
     * @param path the file {@code apply} wrote
     * @return the journal
     * @throws IllegalArgumentException when it is not a journal this build can read, with the
     *     reason
     */
    public static Journal read(Path path) {
        JsonNode tree;
        try {
            tree = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("cannot read the journal " + path + ": "
                    + unreadable.getMessage(), unreadable);
        }
        if (tree == null || !tree.isObject() || !tree.path("format").isInt()) {
            throw new IllegalArgumentException(path + " is not an acemq-infra journal: there is no"
                    + " format number in it.");
        }
        int format = tree.get("format").asInt();
        if (format > FORMAT) {
            throw new IllegalArgumentException(path + " is journal format " + format + ", written"
                    + " by a newer acemq-infra (" + tree.path("tool").asText("unknown") + "). This"
                    + " one reads format " + FORMAT + " and older, and will not guess at the rest:"
                    + " roll back with the version that wrote it.");
        }
        for (String field : List.of("deployment", "file", "fileSha256", "from", "to", "outcome")) {
            if (!tree.path(field).isTextual()) {
                throw new IllegalArgumentException(path + " is not a complete journal: it has no '"
                        + field + "'.");
            }
        }
        if (!tree.path("steps").isArray()) {
            throw new IllegalArgumentException(path + " is not a complete journal: it has no"
                    + " steps.");
        }
        ObjectNode root = (ObjectNode) tree;
        return new Journal(path, root, root);
    }

    // ---------------------------------------------------------------- what the executor records

    /** A step is about to run. Recorded before it can write anything. */
    void starting(int number, int index, String id) {
        ObjectNode step = entry(number);
        step.put("number", number);
        if (index < 0) {
            step.putNull("index");
        } else {
            step.put("index", index);
        }
        step.put("id", id);
        step.put("status", "started");
        save();
    }

    /** A step has finished, one way or another. */
    void taken(int index, Execution.Taken taken) {
        ObjectNode step = entry(taken.number());
        step.put("number", taken.number());
        if (index < 0) {
            step.putNull("index");
        } else {
            step.put("index", index);
        }
        step.put("id", taken.id());
        step.put("status", taken.status().name().toLowerCase(Locale.ROOT));
        ArrayNode lines = step.putArray("lines");
        taken.lines().forEach(lines::add);
        save();
    }

    /** Something now running on a broker, which a rollback must not race. */
    void declared(Broker.Movement movement) {
        ObjectNode entry = ((ArrayNode) section.get("movements")).addObject();
        entry.put("label", movement.label());
        entry.put("on", movement.on());
        ArrayNode parts = entry.putArray("parts");
        movement.parts().forEach(parts::add);
        save();
    }

    /**
     * The run has ended.
     *
     * @param outcome how
     */
    public void finish(Execution.Outcome outcome) {
        section.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
        section.put("endedAt", Instant.now().toString());
        save();
    }

    private ObjectNode entry(int number) {
        ArrayNode steps = (ArrayNode) section.get("steps");
        for (JsonNode step : steps) {
            if (step.path("number").asInt(-1) == number) {
                return (ObjectNode) step;
            }
        }
        return steps.addObject();
    }

    // ---------------------------------------------------------------- what rollback reads

    /** Where this journal is. */
    public Path path() {
        return path;
    }

    /** The deployment file the run read, as an absolute path. */
    public String file() {
        return root.get("file").asText();
    }

    /** The deployment's name. */
    public String deployment() {
        return root.get("deployment").asText();
    }

    /** The cluster the cutover moved away from. */
    public String from() {
        return root.get("from").asText();
    }

    /** The cluster it moved to. */
    public String to() {
        return root.get("to").asText();
    }

    /** How the cutover ended, or {@code running} when nothing recorded an end. */
    public String outcome() {
        return root.get("outcome").asText();
    }

    /**
     * Why this journal does not describe the run about to be undone, if it does not.
     *
     * @param fileBytes the deployment file's bytes as they are now
     * @param from the {@code from} cluster the file names now
     * @param to the {@code to} cluster
     * @param clusters every cluster's management URL as the file resolves it now
     * @return the reason, or empty when the journal and the file agree
     */
    public Optional<String> mismatch(byte[] fileBytes, String from, String to,
                                     Map<String, String> clusters) {
        if (!root.get("fileSha256").asText().equals(sha256(fileBytes))) {
            return Optional.of("the deployment file is not the one this journal was written from:"
                    + " its contents have changed since the cutover (" + file() + "). A rollback"
                    + " is derived from the steps that ran, numbered as that file numbered them, so"
                    + " it needs that file exactly.");
        }
        if (!from().equals(from) || !to().equals(to)) {
            return Optional.of("this journal moved " + from() + " → " + to() + " and the file now"
                    + " says " + from + " → " + to + ".");
        }
        Map<String, String> recorded = new LinkedHashMap<>();
        root.path("clusters").properties()
                .forEach(field -> recorded.put(field.getKey(), field.getValue().asText()));
        for (String name : List.of(from, to)) {
            String then = recorded.get(name);
            String now = clusters.get(name);
            if (then == null || !then.equals(now)) {
                return Optional.of("cluster '" + name + "' was " + then + " when this journal was"
                        + " written and resolves to " + now + " now. The file is the same and a"
                        + " variable is not: this would undo a cutover on clusters it never ran"
                        + " against.");
            }
        }
        return Optional.empty();
    }

    /**
     * When this journal has already been rolled back, or a rollback of it was started.
     *
     * @return what happened, or empty when no rollback has been attempted
     */
    public Optional<String> rolledBack() {
        JsonNode rollback = root.path("rollback");
        if (!rollback.isObject()) {
            return Optional.empty();
        }
        return Optional.of("a rollback of this journal was started at "
                + rollback.path("startedAt").asText("an unknown time") + " and ended "
                + rollback.path("outcome").asText("running"));
    }

    /**
     * The steps the cutover carried out, as the file has them, in the order they ran.
     *
     * <p>{@code done} steps, and — when the journal never recorded an end, which is a crash — any
     * step left at {@code started} too. The backup is not a step in the file and is never undone.
     *
     * @param steps the file's step list, as {@link Run#stepsOf} gives it
     * @return the steps to derive the rollback from
     * @throws IllegalArgumentException when the journal names a step the file does not have
     */
    public List<Step> completed(List<Step> steps) {
        boolean crashed = "running".equals(outcome());
        List<Step> completed = new ArrayList<>();
        for (JsonNode entry : root.get("steps")) {
            String status = entry.path("status").asText();
            boolean happened = status.equals("done") || crashed && status.equals("started");
            if (!happened || !entry.path("index").isInt()) {
                continue;
            }
            int index = entry.get("index").asInt();
            String id = entry.path("id").asText();
            if (index >= steps.size() || !steps.get(index).describeId().equals(id)) {
                throw new IllegalArgumentException("this journal records step " + (index + 1)
                        + " as '" + id + "' and the file does not have that step there. It is"
                        + " not the plan this journal was written from.");
            }
            completed.add(steps.get(index));
        }
        return completed;
    }

    /**
     * The steps a crash left half-done, which {@link #completed} counts as having happened.
     *
     * @return their ids; empty when the run recorded how it ended
     */
    public List<String> interrupted() {
        if (!"running".equals(outcome())) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : root.get("steps")) {
            if (entry.path("status").asText().equals("started")) {
                ids.add(entry.path("id").asText());
            }
        }
        return ids;
    }

    /**
     * Everything the cutover declared on a broker, finished or not.
     *
     * @return the movements, in the order they were declared
     */
    public List<Broker.Movement> movements() {
        List<Broker.Movement> movements = new ArrayList<>();
        for (JsonNode entry : root.path("movements")) {
            List<String> parts = new ArrayList<>();
            entry.path("parts").forEach(part -> parts.add(part.asText()));
            movements.add(new Broker.Movement(entry.path("label").asText(),
                    entry.path("on").asText(), parts));
        }
        return movements;
    }

    /**
     * Marks the rollback as started, before its first write, and returns the journal it records
     * its own steps into.
     *
     * @return a journal writing into this file's {@code rollback} block
     * @throws UncheckedIOException when it cannot be written, which must stop the rollback: a
     *     rollback that was not recorded is one that can be run twice
     */
    public Journal rollback() {
        ObjectNode rollback = root.putObject("rollback");
        rollback.put("tool", root.path("tool").asText());
        rollback.put("startedAt", Instant.now().toString());
        rollback.put("outcome", "running");
        rollback.putArray("steps");
        rollback.putArray("movements");
        try {
            write();
        } catch (IOException failed) {
            throw new UncheckedIOException("the journal " + path + " could not be marked as rolled"
                    + " back: " + failed.getMessage(), failed);
        }
        return new Journal(path, root, rollback);
    }

    /**
     * Takes the rollback mark back off, for a rollback that was refused before its first write.
     * Called on the journal {@link #rollback()} returned.
     */
    public void withdraw() {
        root.remove("rollback");
        save();
    }

    /**
     * Why a write after the first one failed, if one did.
     *
     * @return the reason; empty when every step was recorded
     */
    public Optional<String> broken() {
        return Optional.ofNullable(broken);
    }

    // ---------------------------------------------------------------- the file

    /**
     * Every write after the first. A journal that cannot be updated must not stop a cutover that
     * is halfway through — that would be the tool causing the half-moved estate it exists to
     * record — so the failure is kept and reported at the end instead.
     */
    private void save() {
        try {
            write();
        } catch (IOException failed) {
            if (broken == null) {
                broken = failed.getMessage();
            }
        }
    }

    /** Through a temporary file and a rename, so a reader never sees half a journal. */
    private void write() throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notHere) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A file's fingerprint.
     *
     * @param bytes the contents
     * @return the SHA-256, in lowercase hex
     */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }
}
