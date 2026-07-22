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
 * A typed repair directive produced by {@link RepairPolicyEngine}. Carries the concrete instruction
 * (which data to re-fetch / which reasoning to fix), not a generic "please correct".
 */
public record RepairPlan(
        RepairType type,
        String directive,
        int maxRetry,
        String errorType,
        String severity) {

    public static RepairPlan none() {
        return new RepairPlan(RepairType.NONE, "", 0, null, null);
    }

    public static RepairPlan refuse(String directive) {
        return new RepairPlan(RepairType.REFUSE, directive, 0, "FABRICATION", "CRITICAL");
    }

    public boolean needsAction() {
        return type != RepairType.NONE;
    }
}
