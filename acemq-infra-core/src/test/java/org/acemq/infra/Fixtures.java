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
package org.acemq.infra;

import java.util.List;
import java.util.Map;

import org.acemq.infra.config.DeploymentFile;
import org.acemq.infra.config.DeploymentFileParser;
import org.acemq.infra.config.Environment;
import org.acemq.infra.validate.Finding;
import org.acemq.infra.validate.ValidationReport;
import org.acemq.infra.validate.Validator;

/**
 * Shared scaffolding for the tests.
 *
 * <p>{@link #around(String)} is the useful part. A rule about a drain needs a document with
 * clusters, an endpoint and a header around it, and writing those out in thirty tests would mean
 * thirty places to edit when the schema moves and thirty chances for a test to be passing for the
 * wrong reason. The base it wraps a body in is deliberately clean — no errors at all, and exactly
 * one warning, for the absent backup — so any finding a test sees is the one it asked for.
 */
public final class Fixtures {

    /**
     * The variables the worked examples refer to, with values of the right shape.
     *
     * <p>Shaped like the real thing rather than {@code x}: these are substituted into the text
     * before it is parsed, so a value with a stray colon or newline in it would change what the
     * file means, and a test that passed on {@code x} would say nothing about a file that carries
     * a URL.
     */
    public static final Map<String, String> VARIABLES = Map.ofEntries(
            Map.entry("BLUE_MGMT_URL", "https://blue.internal:15671"),
            Map.entry("BLUE_AMQP_URL", "amqps://blue.internal:5671"),
            Map.entry("BLUE_USERNAME", "cutover"),
            Map.entry("BLUE_PASSWORD", "s3cret-blue"),
            Map.entry("GREEN_MGMT_URL", "https://green.internal:15671"),
            Map.entry("GREEN_AMQP_URL", "amqps://green.internal:5671"),
            Map.entry("GREEN_USERNAME", "cutover"),
            Map.entry("GREEN_PASSWORD", "s3cret-green"),
            Map.entry("CA_FILE", "/etc/ssl/certs/internal-ca.pem"),
            Map.entry("ANNOUNCE_EXCHANGE", "orders.events"));

    /** An environment holding exactly {@link #VARIABLES}. */
    public static final Environment ENVIRONMENT = Environment.of(VARIABLES);

    private Fixtures() {
    }

    /** A valid header, two clusters and an external endpoint, with {@code body} appended. */
    public static String around(String body) {
        return """
                apiVersion: acemq.org/v1alpha1
                kind: Deployment
                metadata:
                  name: test-deployment
                provider: rabbitmq
                clusters:
                  blue:
                    management: https://blue.internal:15671
                    amqp: amqps://blue.internal:5671
                    username: cutover
                    password: s3cret
                  green:
                    management: https://green.internal:15671
                    amqp: amqps://green.internal:5671
                    username: cutover
                    password: s3cret
                endpoint:
                  kind: external
                  description: switched by the platform team
                """ + body;
    }

    /** Parse text as a deployment file, with the fixed environment. */
    public static DeploymentFile parse(String text) {
        return DeploymentFileParser.parse(text, "test.yaml", ENVIRONMENT);
    }

    /** Parse and validate in one go, for the rule tests. */
    public static ValidationReport validate(String text) {
        return Validator.validate(parse(text));
    }

    /** The error messages, in order. */
    public static List<String> errors(ValidationReport report) {
        return report.errors().stream().map(Finding::message).toList();
    }

    /** The warning messages, in order. */
    public static List<String> warnings(ValidationReport report) {
        return report.warnings().stream().map(Finding::message).toList();
    }
}
