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
 * Repair action taxonomy (V3.0 design §10.2, P0). {@code MODIFY_RESULT} / {@code CHANGE_CONCLUSION}
 * are intentionally <b>not</b> members - the Repair Policy Engine forbids them globally to prevent
 * the "rephrase to pass verification" gaming pattern.
 */
public enum RepairType {
    /** Data missing / insufficient / unsourced -> re-query (specify which dimension/time). */
    DATA_REQUERY,
    /** Metric-direction / semantic / arithmetic error -> reload Semantic Contract, fix reasoning, never touch data. */
    SEMANTIC_FIX,
    /** Parameter error (time range / entity name) -> fix param then re-fetch. */
    PARAMETER_FIX,
    /** Question ambiguous / parameter missing -> ask the user, pause the loop. */
    CLARIFY_USER,
    /** Data genuinely cannot support the conclusion or high-risk (financial) -> refuse honestly, no fabrication. */
    REFUSE,
    /** No repair needed (PASS / WARN). */
    NONE;

    /**
     * Parse a repair hint string (from verify.md JSON or repair_policy.yaml). {@code REASONING_FIX}
     * is a V2.1 compatibility alias that maps to {@link #SEMANTIC_FIX}; {@code ABORT} maps to
     * {@link #REFUSE}; {@code CLARIFICATION} maps to {@link #CLARIFY_USER}.
     */
    public static RepairType fromHint(String hint) {
        if (hint == null || hint.isBlank()) return NONE;
        return switch (hint.trim().toUpperCase()) {
            case "DATA_REQUERY" -> DATA_REQUERY;
            case "SEMANTIC_FIX", "REASONING_FIX" -> SEMANTIC_FIX;
            case "PARAMETER_FIX" -> PARAMETER_FIX;
            case "CLARIFY_USER", "CLARIFICATION", "ASK_USER" -> CLARIFY_USER;
            case "REFUSE", "ABORT" -> REFUSE;
            default -> NONE;
        };
    }
}
