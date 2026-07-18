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
package com.agentscopea2a.v2.hooks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@code ARITH_PATTERN} used by {@link ArithMentalMathDetectorHook}.
 *
 * <p>The hook itself fires from inside the agentscope framework's PostCallEvent pipeline and
 * is awkward to drive in a pure unit test (needs Memory / Msg / ContentBlock fixtures). The
 * regex is the part that's easy to get wrong - false positives make the warn noisy, false
 * negatives make it useless - so we cover it directly via reflection.
 */
class ArithMentalMathDetectorHookPatternTest {

    private static String detect(String text) throws Exception {
        Method contains = ArithMentalMathDetectorHook.class
                .getDeclaredMethod("containsArithmetic", String.class);
        contains.setAccessible(true);
        return (boolean) contains.invoke(null, text) ? "HIT" : "MISS";
    }

    @Test
    void detectsAsciiArithmetic() throws Exception {
        assertTrue("HIT".equals(detect("缺陷率 = 5 + 3 = 8 个")),
                "should detect 5 + 3");
        assertTrue("HIT".equals(detect("提升了 100 / 4 倍")),
                "should detect 100 / 4");
        assertTrue("HIT".equals(detect("差值 = 1.5 - 0.3")),
                "should detect 1.5 - 0.3");
    }

    @Test
    void ignoresPlainNumbers() throws Exception {
        assertFalse("HIT".equals(detect("版本 2.0.0")),
                "version number should not trigger");
        assertFalse("HIT".equals(detect("2026年07月19日")),
                "date should not trigger");
        assertFalse("HIT".equals(detect("杭州开发一部")),
                "department name should not trigger");
        assertFalse("HIT".equals(detect("缺陷率 20%")),
                "single percentage with no operator should not trigger");
    }
}
