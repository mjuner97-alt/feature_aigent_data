/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.artifact;

import com.agentscopea2a.v2.artifact.TabularExtractor.ColumnSchema;
import java.util.List;

/**
 * Handle returned by {@link ArtifactStore#save} — what the producing tool gets and what it weaves
 * into the handoff message to downstream subagents.
 *
 * @param id            opaque artifact id (tool prefix + UUID), uniquely identifies the file
 * @param agentPath     the path the subagent should use to read this artifact (host absolute
 *                      path in default mode, container-internal path in sandbox mode)
 * @param hostPath      the path on the host where the file actually lives — used by
 *                      {@link ArtifactStore#cleanupTask} for GC, NEVER exposed to the LLM
 * @param columns       inferred CSV column names, used in the handoff message preview
 * @param rows          total row count, used in the handoff message preview
 * @param previewMarkdown markdown table showing the first few rows, embedded in the tool result
 *                      so the supervisor can sanity-check the data without reading the file
 * @param schema        per-column dtype / non-null / sample / range — populated when the artifact
 *                      was produced from a {@code TabularData} parse, {@code null} otherwise.
 *                      Used by {@code ArtifactHandoffHook} to inline an exec-ready schema block so
 *                      downstream {@code code_interpreter} skips df.head/dtypes round-trips.
 */
public record ArtifactRef(
        String id,
        String agentPath,
        String hostPath,
        List<String> columns,
        int rows,
        String previewMarkdown,
        List<ColumnSchema> schema) {

    /** Back-compat ctor for callers that don't yet pass a schema (e.g. fail-soft path). */
    public ArtifactRef(
            String id,
            String agentPath,
            String hostPath,
            List<String> columns,
            int rows,
            String previewMarkdown) {
        this(id, agentPath, hostPath, columns, rows, previewMarkdown, null);
    }
}
