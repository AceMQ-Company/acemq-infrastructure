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
package org.acemq.infra.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrdTest {

    @Test
    @DisplayName("deploy/crd.yaml is the CRD the build generates from Cutover")
    void shipped() throws IOException {
        String generated = Files.readString(
                Path.of("target/classes/META-INF/fabric8/cutovers.infra.acemq.org-v1.yml"));
        assertThat(Files.readString(Path.of("../deploy/crd.yaml")))
                .as("regenerate it: cp acemq-infra-operator/target/classes/META-INF/fabric8/"
                        + "cutovers.infra.acemq.org-v1.yml deploy/crd.yaml")
                .isEqualTo(generated);
    }
}
