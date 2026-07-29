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
package com.agentscopea2a.v2.middleware;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PythonExecAccessMiddleware}, focusing on the
 * {@code repairIncompleteArtifactPaths} method.
 */
class PythonExecAccessMiddlewareTest {

    private static final String ARTIFACTS_ROOT = "/workspace/artifacts/";
    private static final String ALLOWED_PREFIX = "/workspace/artifacts/13272/218aa6ee-abc/";

    /**
     * Access the private static method via reflection for testing.
     */
    private static String invokeRepair(String code, String root, String prefix) throws Exception {
        Method m = PythonExecAccessMiddleware.class.getDeclaredMethod(
                "repairIncompleteArtifactPaths", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, code, root, prefix);
    }

    @Test
    void repairIncompletePath_doesNotTruncateCode() throws Exception {
        // This is the exact bug: the old code omitted sb.append(code, from, code.length())
        // after the while loop, causing all Python code after the last path replacement
        // to be silently dropped.
        String code = "import pandas as pd\n"
                + "df = pd.read_csv(\"/workspace/artifacts/quality_q1_monthly.csv\")\n"
                + "print(df.head())\n"
                + "result = df.describe()\n"
                + "print(result)\n";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        // The incomplete path should be expanded
        assertNotNull(repaired);
        assertTrue(repaired.contains(ALLOWED_PREFIX), "Should contain allowed prefix");
        assertTrue(repaired.contains("quality_q1_monthly.csv"), "Filename preserved");

        // CRITICAL: code after the path must NOT be truncated
        assertTrue(repaired.contains("print(df.head())"), "Code after path must be preserved");
        assertTrue(repaired.contains("result = df.describe()"), "Code after path must be preserved");
        assertTrue(repaired.contains("print(result)"), "Code after path must be preserved");
    }

    @Test
    void repairIncompletePath_closingQuotePreserved() throws Exception {
        // The user's bug: path like /workspace/artifacts/file.csv" was losing
        // the closing quote and everything after it
        String code = "path = \"/workspace/artifacts/data.csv\"\nprint(path)";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        assertNotNull(repaired);
        assertTrue(repaired.contains(ALLOWED_PREFIX + "data.csv"), "Path should be expanded");
        assertTrue(repaired.contains("\"\nprint(path)"), "Closing quote and subsequent code preserved");
    }

    @Test
    void repairIncompletePath_alreadyCorrectPath() throws Exception {
        String code = "df = pd.read_csv(\"/workspace/artifacts/13272/218aa6ee-abc/data.csv\")\nprint(df)";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        // Already has the correct prefix — no repair needed
        assertNull(repaired, "No repair needed for already-correct path");
    }

    @Test
    void repairIncompletePath_crossTenantPathNotRepaired() throws Exception {
        // A cross-tenant path (has slashes after root) should NOT be repaired;
        // it should be left for the cross-tenant check to handle
        String code = "df = pd.read_csv(\"/workspace/artifacts/otheruser/task1/data.csv\")\nprint(df)";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        // The path has slashes after root, so it's not an "incomplete" path — no repair
        assertNull(repaired, "Cross-tenant path should not be repaired by this method");
    }

    @Test
    void repairIncompletePath_multiplePaths() throws Exception {
        String code = "a = \"/workspace/artifacts/file1.csv\"\nb = \"/workspace/artifacts/file2.csv\"\nprint(a+b)";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        assertNotNull(repaired);
        assertTrue(repaired.contains(ALLOWED_PREFIX + "file1.csv"), "First path expanded");
        assertTrue(repaired.contains(ALLOWED_PREFIX + "file2.csv"), "Second path expanded");
        assertTrue(repaired.contains("print(a+b)"), "Code after all paths preserved");
    }

    @Test
    void repairIncompletePath_noArtifactPath() throws Exception {
        String code = "print('hello world')";

        String repaired = invokeRepair(code, ARTIFACTS_ROOT, ALLOWED_PREFIX);

        assertNull(repaired, "No repair needed when no artifact paths present");
    }

    @Test
    void repairIncompletePath_nullCode() throws Exception {
        String repaired = invokeRepair(null, ARTIFACTS_ROOT, ALLOWED_PREFIX);
        assertNull(repaired);
    }

    @Test
    void repairIncompletePath_emptyRoot() throws Exception {
        String repaired = invokeRepair("some code", "", ALLOWED_PREFIX);
        assertNull(repaired);
    }
}