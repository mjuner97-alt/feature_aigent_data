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

import java.util.List;

/**
 * One row of {@code repair_policy.yaml} / {@code repair_policy_rule} table (V3.0 design §10.3, P0).
 * Matched by {@code errorType + severity}; {@link #firstAllowed()} yields the repair action, which
 * the engine then checks against {@code forbidden} + the global forbid set.
 */
public record RepairPolicyRule(
        String ruleId,
        String errorType,
        String severity,
        List<String> allowedActions,
        List<String> forbidden,
        int maxRetry,
        int priority,
        boolean enabled) {

    /** First allowed action as a typed {@link RepairType}; REFUSE if none declared. */
    public RepairType firstAllowed() {
        if (allowedActions == null || allowedActions.isEmpty()) {
            return RepairType.REFUSE;
        }
        return RepairType.fromHint(allowedActions.get(0));
    }

    public boolean forbids(RepairType type) {
        if (type == null || type == RepairType.NONE) return false; // "no action" is never forbidden
        if (forbidden != null) {
            for (String f : forbidden) {
                if (RepairType.fromHint(f) == type) return true;
            }
        }
        return false;
    }
}
