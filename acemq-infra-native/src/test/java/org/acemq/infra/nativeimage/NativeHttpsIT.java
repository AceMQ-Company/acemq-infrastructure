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
package org.acemq.infra.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The binary over TLS, because every management URL that matters is {@code https} and no container
 * in this suite serves one.
 *
 * <p>This test exists because of a flag that is no longer there. GraalVM used to need
 * {@code --enable-url-protocols=https} to put the protocol in the image at all; the option is now
 * deprecated, the protocol is meant to be present without asking, and "meant to be" is not a thing
 * to find out about during somebody's cutover. The lab is plain HTTP — it is two containers on a
 * Docker network — so without this the entire suite could pass against a binary that cannot dial a
 * single real broker.
 *
 * <p>What is asserted is deliberately not a message. The server counts the requests that reach its
 * handler, and a request only reaches a handler after the handshake has succeeded, so one request
 * is positive proof of the whole chain: the {@code https} protocol, the TLS implementation, the
 * PKCS12 truststore being read off disk, and the trust manager factory that decides. A test that
 * asserted on the text of a failure would pass just as happily when the failure was the wrong one.
 *
 * <p>The truststore reaches the binary as {@code -Djavax.net.ssl.trustStore}, which a native image
 * parses before {@code main} exactly as a JVM does. That is also worth knowing on its own: it is
 * how an estate with a private certificate authority points this tool at their own roots, and the
 * answer being "the same way as any other Java program" is only true if somebody checks.
 */
@DisplayName("the binary over TLS")
class NativeHttpsIT {

    @TempDir
    private static Path work;

    private static HttpsServer server;

    /** How many requests got past the handshake, which is the whole assertion. */
    private static final AtomicInteger ARRIVED = new AtomicInteger();

    private static Path truststore;

    @BeforeAll
    static void serveHttps() throws Exception {
        Path keystore = work.resolve("server.p12");
        Path certificate = work.resolve("server.crt");
        truststore = work.resolve("trust.p12");

        // keytool rather than a certificate written in Java: building an X.509 by hand needs
        // internal JDK packages that are not exported, and every machine this builds on has the
        // JDK's own tool sitting next to the compiler.
        keytool("-genkeypair", "-alias", "lab", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit");
        keytool("-exportcert", "-alias", "lab", "-rfc", "-file", certificate.toString(),
                "-keystore", keystore.toString(), "-storepass", "changeit");
        keytool("-importcert", "-noprompt", "-alias", "lab", "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", truststore.toString(),
                "-storepass", "changeit");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream from = Files.newInputStream(keystore)) {
            keys.load(from, "changeit".toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            ARRIVED.incrementAndGet();
            // Deliberately not a broker. What happens after the handshake is the probe's business
            // and is tested against real brokers; all that is claimed here is that the request got
            // this far, and a plausible-looking management API would only blur that.
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServing() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("completes a TLS handshake against a certificate the estate supplied")
    void theHandshake() {
        Path file = Lab.file(work, "https.yaml", """
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: over-tls

                provider: rabbitmq

                clusters:
                  blue:
                    management: https://localhost:%d
                    amqp: amqp://guest:guest@blue:5672
                    vhost: /
                    username: guest
                    password: guest
                  green:
                    management: https://localhost:%d
                    amqp: amqp://guest:guest@green:5672
                    vhost: /
                    username: guest
                    password: guest

                endpoint:
                  kind: external
                  description: not reached — this file exists to make one TLS connection.

                deployment:
                  operation: blueGreen
                  from: blue
                  to: green
                  semantics: atLeastOnce
                """.formatted(server.getAddress().getPort(), server.getAddress().getPort()));

        Binary.Result probed = Binary.run(
                "-Djavax.net.ssl.trustStore=" + truststore,
                "-Djavax.net.ssl.trustStorePassword=changeit",
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "plan", "-f", file.toString());

        assertThat(ARRIVED.get())
                .withFailMessage("nothing reached the HTTPS server, so the binary never completed a"
                        + " handshake. What it said was:%n%s", probed.all())
                .isPositive();

        // The probe is expected to fail: it was answered 503 by something that is not RabbitMQ.
        // What it must not do is fall over in a way that reads as a broken tool rather than as an
        // unreachable cluster.
        assertThat(probed.status()).isNotZero();
        assertThat(probed.all()).doesNotContain("Exception in thread");
    }

    private static void keytool(String... arguments) {
        Path tool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new java.util.ArrayList<>();
        command.add(tool.toString());
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String said = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + said);
            }
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
