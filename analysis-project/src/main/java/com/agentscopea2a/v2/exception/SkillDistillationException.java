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
package com.agentscopea2a.v2.exception;

/**
 * Skill distillation failures (LLM call failed, SKILL.md parsing failed, save-to-disk failed).
 *
 * <p>Used by {@code SkillDistiller} / {@code SkillSynthesisRunner} when a candidate crosses the
 * hit threshold but the distillation step cannot produce a valid SKILL.md. The candidate row
 * is left in {@code rejected} status so it doesn't keep tripping the threshold.
 */
public class SkillDistillationException extends V2RuntimeException {

    public SkillDistillationException(String message) {
        super(message);
    }

    public SkillDistillationException(String message, Throwable cause) {
        super(message, cause);
    }
}
