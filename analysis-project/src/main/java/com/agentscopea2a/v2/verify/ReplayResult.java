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
 * Replay result (V3.0 design §15/§16, P1). Compares the original recorded verdict with the
 * replayed verdict for one session, across {@link ReplayService.ReplayMode}.
 */
public record ReplayResult(
        String sessionId,
        String mode,
        Integer originalTrustScore,
        String originalVerdict,
        Integer replayedTrustScore,
        String replayedVerdict,
        String replayedSummary,
        int eventCount,
        String candidateConclusion,
        String notes) {
}
