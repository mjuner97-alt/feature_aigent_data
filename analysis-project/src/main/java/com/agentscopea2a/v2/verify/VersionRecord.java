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
package com.agentscopea2a.v2.verify;

/**
 * One row of {@code agent_version_registry} (V3.0 design §14/§23.6, P1). A versioned snapshot of a
 * component (Agent / Prompt / Skill / SemanticContract / RepairPolicy / Tool) so Golden Evaluation
 * and Replay can pin a version combination and reproduce results.
 */
public record VersionRecord(
        String versionId,
        String component,
        String componentRef,
        String version,
        String checksum,
        String releasedBy,
        java.time.Instant releasedAt,
        String goldenEvalId,
        String status) {
}
