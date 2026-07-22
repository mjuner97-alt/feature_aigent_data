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
 * Result of the V4.0 auto-apply step (完整自动优化闭环). Threshold tweaks proposed by
 * {@link QualityOptimizationLoop} are applied (capped) to {@link CalibrationState}; the before/after
 * thresholds and the Golden eval id (for gate validation) are recorded so a rollback can restore the
 * prior state if the eval regresses.
 */
public record OptimizationApplyResult(
        int appliedCount,
        int passThresholdBefore,
        int passThresholdAfter,
        int warnThresholdBefore,
        int warnThresholdAfter,
        String goldenEvalId,
        boolean rolledBack,
        String reason) {
}
