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
package org.acemq.infra.validate;

import java.util.List;

import org.acemq.infra.config.DeploymentFile;

/**
 * Everything the validator found, and the document it found it in.
 *
 * <p>A report rather than an exception, because a file with one error and three warnings should
 * produce all four lines and not just the first. The document travels with the findings so that a
 * caller printing the report can head it with the summary — {@code blueGreen, 9 steps, 2
 * clusters} — without parsing the file a second time.
 *
 * <p>How this is rendered belongs to whatever is doing the rendering. The CLI has a format, a
 * pipeline might want one line per finding, and a test wants the fields; a report that could only
 * be printed one way would force all three to agree.
 *
 * @param document the file these findings are about
 * @param findings everything found, in the order the file writes it
 */
public record ValidationReport(DeploymentFile document, List<Finding> findings) {

    public ValidationReport {
        findings = List.copyOf(findings);
    }

    /** Whether the file can be planned: true when there are no errors, warnings notwithstanding. */
    public boolean ok() {
        return findings.stream().noneMatch(Finding::isError);
    }

    /** The findings that stop the file being planned. */
    public List<Finding> errors() {
        return findings.stream().filter(Finding::isError).toList();
    }

    /** The findings that do not. */
    public List<Finding> warnings() {
        return findings.stream().filter(finding -> !finding.isError()).toList();
    }

    /** The one-line description of what this file does, for the head of a report. */
    public String summary() {
        return document.summary();
    }
}
