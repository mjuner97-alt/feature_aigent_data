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

import org.springframework.stereotype.Component;

/**
 * Maps the final Trust Score to a user-facing response strategy (V3.0 design §18):
 * >= directThreshold (90) -> direct answer; hintThreshold (70) - 89 -> answer + advisory hint;
 * < 70 -> request clarification / refuse.
 */
@Component
public class ResponsePolicyResolver {

    public static final String DIRECT = "direct";
    public static final String HINT = "hint";
    public static final String CLARIFY = "clarify";

    private final CalibrationState calibrationState;

    public ResponsePolicyResolver(CalibrationState calibrationState) {
        this.calibrationState = calibrationState;
    }

    public String resolveResponseMode(int trustScore) {
        if (trustScore >= calibrationState.getDirectThreshold()) {
            return DIRECT;
        }
        if (trustScore >= calibrationState.getHintThreshold()) {
            return HINT;
        }
        return CLARIFY;
    }
}
