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
import java.nio.file.Path;

/**
 * The shape of a cluster, as something the executor can carry from one broker to another and
 * cannot read.
 *
 * <p>An interface with two methods rather than a record holding a document, and the reason is on
 * docs/message-state.md's last page. On RabbitMQ this is a definitions export, and a definitions
 * export carries users, and users carry password hashes — a hash being enough to stand up a broker
 * the real passwords authenticate against. So the document is a credential, and the executor, which
 * logs what it does and writes a report somebody will paste into an incident channel, never holds
 * one as a string it could print by accident.
 *
 * <p>{@link #describe()} is what a report may say. {@link #writeTo(Path, boolean)} is the backup,
 * and it is on this type rather than on {@link Broker} because only the provider knows what
 * redacting its own document means.
 */
public interface Topology {

    /** The cluster this was read from, by the name the deployment file gave it. */
    String from();

    /** {@code 14 exchanges, 31 queues, 58 bindings} — counts, and nothing that is a secret. */
    String describe();

    /**
     * Writes the document to disk, which is what {@code deployment.backup} is.
     *
     * <p>Redaction is the default everywhere that calls this, and the consequence is stated rather
     * than left to be discovered during a restore: a redacted backup is not by itself enough to
     * bring a cluster back, because the users in it have no password data. Written unredacted, on
     * purpose, the file is created {@code 0600} and the path is reported.
     *
     * @param path where to write it
     * @param redactCredentials whether to strip the password data from the users and permissions
     * @return how many bytes were written
     * @throws IOException if the file could not be written
     */
    long writeTo(Path path, boolean redactCredentials) throws IOException;
}
