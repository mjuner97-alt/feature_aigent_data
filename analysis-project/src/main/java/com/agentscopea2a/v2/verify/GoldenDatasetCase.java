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
 * One row of {@code golden_dataset_case} (V3.0 design §13.3, P0). A benchmark question with its
 * expected answer / metric, used by {@link GoldenEvaluationRunner} to detect capability regression
 * after Agent / Prompt / Skill / Semantic-Contract upgrades.
 */
public record GoldenDatasetCase(
        String caseId,
        String question,
        String category,
        String expectedSql,
        String expectedAnswer,
        String expectedMetric,
        String difficulty,
        String tags,
        String version) {
}
