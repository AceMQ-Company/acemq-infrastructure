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
package org.acemq.infra.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.acemq.infra.config.RestartAt;
import org.acemq.infra.config.Streams;
import org.acemq.infra.provider.Inventory;

/**
 * What each stream consumer will actually do when its stream has been moved.
 *
 * <p>docs/roadmap.md puts three things in phase 3 and this is two of them: the per-consumer
 * projection, and the {@code streams.acknowledged} confirmation that the projection is what the
 * confirmation is about. The third, the refusal, is the shape of the whole thing rather than a part
 * of it — the projection is printed so that a refusal can be lifted honestly, and lifting it is a
 * line somebody writes in the file rather than a flag somebody passes.
 *
 * <h2>Why there is a projection rather than an answer</h2>
 *
 * <p>An offset is a position in one specific log. docs/message-state.md is flat about the
 * consequence: there is no API, at any version, for writing a consumer's offset into another
 * cluster's stream, and there could not usefully be one, because the target's log is a different
 * log with different numbers in it. A shovel makes that worse rather than better — it consumes the
 * source's stream and republishes, so the target's log holds new messages with new offsets and new
 * timestamps, and anything consuming by timestamp is looking at the time of the migration.
 *
 * <p>So the tool has nothing to offer except an accurate sentence per consumer, and the sentence is
 * different for each setting. A single warning saying "offsets do not travel" is true and useless:
 * the operator's question is what <em>their</em> consumers will do, and the answer is
 * {@code audit-writer replays the whole log} for one of them and {@code ledger-tailer never sees
 * the window} for the next.
 *
 * <h2>What {@code restartAt} is for</h2>
 *
 * <p>It sets nothing — nothing here can. It is the operator's statement of what they believe their
 * consumers are configured to do, and holding it up against what the consumers actually asked for
 * is the only thing that makes {@code acknowledged: true} mean something. A file that accepts a gap
 * and whose consumers are configured to replay the entire retained log has acknowledged the wrong
 * consequence, and that is refused rather than warned about: the confirmation is the gate, so a
 * confirmation about the wrong thing is not a gate at all.
 *
 * <p>It is written either as one value for the whole scope or as one value per stream, because an
 * estate can owe two different answers at once and a single value could not describe that estate
 * at all — no value matched, so every honest file was refused and the only way past the refusal
 * was to stop running the tool. {@link RestartAt} carries which of the two spellings the file
 * used, and the difference lands here: a stream the mapping does not name is a refusal rather than
 * a default, for exactly the reason the field exists at all.
 */
public final class StreamRestart {

    private StreamRestart() {
    }

    /**
     * The projection for one estate's streams.
     *
     * @param refusals reasons the cutover must not run as written; empty means it may
     * @param warnings things that are true and worth saying
     * @param lines one line per stream consumer, saying what it will do
     */
    public record Projection(List<String> refusals, List<String> warnings, List<String> lines) {

        public Projection {
            refusals = List.copyOf(refusals);
            warnings = List.copyOf(warnings);
            lines = List.copyOf(lines);
        }
    }

    /**
     * Projects what moving these streams will do to the consumers attached to them.
     *
     * @param streams the streams a drain has selected, which is where the trouble is
     * @param consumers who is attached to them on the cluster being moved away from
     * @param block the file's {@code streams:} block, or empty when it has none
     * @param cluster the name the file gave the cluster being moved away from
     * @return the projection
     */
    public static Projection of(List<Inventory.Queue> streams, Inventory.Consumers consumers,
                                Optional<Streams> block, String cluster) {
        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (streams.isEmpty()) {
            return new Projection(refusals, warnings, lines);
        }

        String names = String.join(", ", streams.stream().map(Inventory.Queue::name).toList());
        lines.add(Text.count(streams.size(), "stream") + " in scope (" + names + "). Offsets do not"
                + " travel between clusters and no API at any version can write one into another"
                + " cluster's log, so every consumer restarts at whatever its x-stream-offset"
                + " says.");

        if (!consumers.observed()) {
            // The projection is the thing being acknowledged, so a projection nobody can make is a
            // confirmation about nothing. Refused for the same reason the canary's consumer check
            // is, and the fix is the same permission.
            refusals.add("a stream is in the drain's scope and the consumers of " + cluster
                    + " could not be listed, so what each of them will do after the move cannot be"
                    + " projected: " + consumers.whyNot() + ". streams.acknowledged confirms a"
                    + " consequence, and this run cannot say what the consequence is.");
            return new Projection(refusals, warnings, lines);
        }

        RestartAt declared = block.map(Streams::restartAt).orElseGet(RestartAt::none);
        Set<String> disagreeing = new LinkedHashSet<>();
        Set<String> uncovered = new LinkedHashSet<>();
        int attached = 0;
        for (Inventory.Queue stream : streams) {
            Optional<String> wanted = declared.forStream(stream.name());
            // Before the consumers, and deliberately. Whether this stream happens to have one
            // attached at the instant of the probe is a property of the instant; whether the file
            // covers it is a property of the file. A mapping that only had to name the streams
            // somebody was consuming this afternoon would be a confirmation whose reach moved
            // with the timing of a probe.
            if (declared.perStream() && wanted.isEmpty()) {
                uncovered.add(stream.name());
            }
            List<Inventory.Consumer> on = consumers.on(stream.name());
            if (on.isEmpty()) {
                lines.add(stream.name() + ": nothing is consuming it on " + cluster + ", so there"
                        + " is no position to lose — whatever attaches on the other side starts"
                        + " where its own x-stream-offset says.");
                continue;
            }
            for (Inventory.Consumer consumer : on) {
                attached++;
                String asked = consumer.streamOffset().orElse("");
                lines.add(stream.name() + " · " + consumer.user() + " (" + consumer.connection()
                        + "): " + describe(asked));
                wanted.filter(one -> !one.equals(spelling(asked)))
                        .ifPresent(one -> disagreeing.add(consumer.user() + " on "
                                + stream.name() + " asks for " + spelling(asked)
                                + " where the file says " + one));
            }
        }

        for (String named : declared.streams()) {
            if (streams.stream().noneMatch(stream -> stream.name().equals(named))) {
                // Said rather than refused. Nothing is checked against this line, so it cannot
                // make the confirmation wrong -- and when it is the typo it usually is, the
                // stream it was meant to name is missing too and that half is a refusal.
                warnings.add("streams.restartAt names " + named + ", which is not a stream in the"
                        + " drain's scope on " + cluster + ". Nothing is checked against that"
                        + " line: either the scope moved or the name is misspelled.");
            }
        }

        if (attached == 0) {
            warnings.add("the streams in scope have no consumers on " + cluster + " at all. That"
                    + " is either a workload that is not running or a set of consumers this"
                    + " management account cannot see attached to queues it can.");
        }

        block.flatMap(Streams::note).ifPresent(note -> lines.add("streams.note: " + note));

        if (!block.map(Streams::confirmed).orElse(false)) {
            // docs/message-state.md: the tool refuses to plan a stream step silently. A refusal
            // rather than a warning because the consequence -- a week of reprocessing, or a gap
            // nobody can enumerate afterwards -- leaves no trace in the estate to find it by.
            refusals.add("a stream is in the drain's scope and streams.acknowledged is not set."
                    + " Stream offsets cannot be moved between clusters; the projection above is"
                    + " what each consumer will do, and the file has to say in writing that it is"
                    + " accepted.");
            return new Projection(refusals, warnings, lines);
        }

        if (!uncovered.isEmpty()) {
            // The case a default would quietly swallow. A mapping that names some of the scope and
            // not the rest is exactly the failure this whole check exists to prevent -- a
            // confirmation covering a consequence the file never described -- and it arrives
            // looking like a complete file rather than like a missing one. So there is no
            // fallback: not to `next`, not to the other streams' answer, not to anything.
            refusals.add("streams.restartAt is written per stream and says nothing about "
                    + String.join(", ", uncovered) + ", which the drain's scope includes. A stream"
                    + " with no line would have to fall back to a position nobody wrote down, and"
                    + " streams.acknowledged would then be signing for a consequence this file"
                    + " never described. Name every stream in scope, or write one value for all of"
                    + " them.");
        }

        if (!disagreeing.isEmpty()) {
            refusals.add("streams.restartAt is not what the consumers are asking for: "
                    + String.join("; ", disagreeing) + ". Nothing"
                    + " here can set an offset — restartAt is a statement about what the consumers"
                    + " are configured to do — so streams.acknowledged has accepted a consequence"
                    + " other than the one that will happen. Fix the consumers or fix the file.");
        }

        if (!uncovered.isEmpty() || !disagreeing.isEmpty()) {
            return new Projection(refusals, warnings, lines);
        }

        lines.add("streams.acknowledged is set, so the plan proceeds"
                + declared.everywhere().map(wanted -> " and every consumer asks for " + wanted)
                        .orElse(declared.perStream()
                                ? " and every consumer asks for what streams.restartAt says for"
                                        + " its stream"
                                : "")
                + ".");
        return new Projection(refusals, warnings, lines);
    }

    /**
     * What one {@code x-stream-offset} will do once the stream is somewhere else.
     *
     * <p>Five settings and five different failures, which is the reason this is a projection rather
     * than a warning. The two named in docs/message-state.md are the ends of the range — replay
     * everything, or see none of the window — and the three in between fail in ways that are
     * harder to describe afterwards because they depend on the target's chunking and on the time
     * the shovel ran rather than on anything anybody chose.
     */
    private static String describe(String asked) {
        if (asked.isEmpty()) {
            return "no x-stream-offset, which RabbitMQ reads as `next` — it will start after"
                    + " whatever the target's log holds when it attaches, so everything the drain"
                    + " republished before that instant is never delivered to it";
        }
        String offset = asked.trim();
        if (offset.equalsIgnoreCase("first")) {
            return "`first` — replays the target's entire retained log from the beginning. Every"
                    + " message the drain republished is delivered again, including the ones this"
                    + " consumer had already processed on the source";
        }
        if (offset.equalsIgnoreCase("next")) {
            return "`next` — starts after whatever the target's log holds when it attaches."
                    + " Everything the drain republished before that instant is never delivered,"
                    + " and nothing afterwards records which messages those were";
        }
        if (offset.equalsIgnoreCase("last")) {
            return "`last` — starts at the beginning of the last chunk the target happens to hold."
                    + " How much it replays is a property of the target's chunking rather than a"
                    + " number anybody chose, and it is not the chunking the source had";
        }
        if (offset.chars().allMatch(Character::isDigit)) {
            return "absolute offset " + offset + " — a position in the SOURCE's log, applied to the"
                    + " target's. A shovel republishes, so the target's numbering is its own and"
                    + " the message at that position is unrelated to the one this consumer stopped"
                    + " at";
        }
        // Anything else the broker reported. A timestamp is the documented case and the one worth
        // naming; an argument this does not recognise is still reported as written rather than
        // guessed at, because a wrong sentence here is worse than an incomplete one.
        return "`" + offset + "` — a timestamp or an interval, resolved against the TARGET's"
                + " timestamps. A shovel republishes, so those are the times the migration ran and"
                + " not the times the messages were produced";
    }

    /** How a setting is spelled for comparison against {@code restartAt}: absent means `next`. */
    private static String spelling(String asked) {
        return asked.isEmpty() ? "next" : asked.trim();
    }
}
