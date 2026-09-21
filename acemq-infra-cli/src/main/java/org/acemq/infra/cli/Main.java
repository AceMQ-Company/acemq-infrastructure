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
package org.acemq.infra.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.acemq.infra.config.Environment;
import org.acemq.infra.execute.rabbitmq.RabbitBroker;
import org.acemq.infra.provider.rabbitmq.RabbitProbe;

/**
 * The entry point, and nothing but the entry point.
 *
 * <p>Everything a process has and a test does not — the real environment, the real streams, the
 * real broker, the human at the keyboard, the right to end the process — is wired here and only
 * here, so that {@link Cli} is a thing a test constructs and calls.
 *
 * <p>The line that matters most is the console. Whether anybody is watching is decided by
 * {@link Terminal} from the process this actually is, and this is the only place that decision is
 * taken: no argument reaches it, so no argument can claim a person is present who is not.
 */
public final class Main {

    private Main() {
    }

    /**
     * @param arguments the command line
     */
    public static void main(String[] arguments) {
        // UTF-8 explicitly, rather than whatever the platform hands over. The plan output uses
        // ticks and crosses in the probe summary and an arrow between the clusters, and on a JDK
        // before 18 System.out takes its encoding from the platform -- so the same plan renders
        // correctly in one shell and as question marks in another, which is a fine way to make an
        // artifact that goes into a pull request look broken.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8);

        int status = new Cli(out, err, Environment.system(), new RabbitProbe(), RabbitBroker::open,
                Terminal.on(out)).run(arguments);
        out.flush();
        err.flush();
        System.exit(status);
    }
}
