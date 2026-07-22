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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for {@link GoldenEvaluationRunner#matchExpected} (V3.0 design §13, P0 Golden
 * accuracy matching): expected-answer containment after stripping the verification annotation and
 * normalizing whitespace.
 */
class GoldenEvaluationRunnerTest {

    @Test
    void matchesExpectedEntity() {
        assertThat(GoldenEvaluationRunner.matchExpected(
                "杭州开发五部的缺陷密度最高，为26.1", "杭州开发五部")).isTrue();
    }

    @Test
    void matchesExact() {
        assertThat(GoldenEvaluationRunner.matchExpected("杭州开发三部", "杭州开发三部")).isTrue();
    }

    @Test
    void doesNotMatchWrongEntity() {
        assertThat(GoldenEvaluationRunner.matchExpected("杭州开发一部", "杭州开发五部")).isFalse();
    }

    @Test
    void matchesNumericAnswer() {
        assertThat(GoldenEvaluationRunner.matchExpected("杭州开发一部的缺陷密度为 23.1", "23.1")).isTrue();
    }

    @Test
    void stripsVerificationAnnotationBeforeMatching() {
        String actual = "杭州开发三部质量最好\n---\n🔍 验证提示\n> 信任评分: 95/100 (PASS)\n---";
        assertThat(GoldenEvaluationRunner.matchExpected(actual, "杭州开发三部")).isTrue();
    }

    @Test
    void nullActualIsFalse() {
        assertThat(GoldenEvaluationRunner.matchExpected(null, "杭州开发五部")).isFalse();
    }
}
