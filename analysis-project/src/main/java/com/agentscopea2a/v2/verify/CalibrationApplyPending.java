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
 * One pending auto-apply awaiting Golden gate validation (V4.0 §31.2 完整自动优化闭环). The
 * {@link CalibrationRollbackWatcher} polls these; on completion it promotes (gate passed) or rolls
 * the calibration back to {@code passBefore}/{@code warnBefore} (gate failed / timed out).
 */
public record CalibrationApplyPending(
        String evalId,
        int passBefore,
        int warnBefore,
        long startedAtMs,
        String status) {
}
