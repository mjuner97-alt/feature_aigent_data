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
package com.agentscopea2a.harness.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentscopea2a.harness.artifact.TabularExtractor.TabularData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The P3 hook makes the all-important call "is this tool output a table I should artifactize?"
 * purely on the basis of {@link TabularExtractor#tryParse(String)}. These tests lock the
 * detection contract:
 *
 * <ul>
 *   <li>real markdown tables with ≥4 data rows → parsed
 *   <li>tables below threshold → null (leave inline, cheaper)
 *   <li>narrative text containing pipe characters → null (no false positives)
 *   <li>JSON arrays of homogeneous objects → parsed
 *   <li>heterogeneous JSON arrays → null
 *   <li>CSV round-trip preserves columns + cells, with proper quoting
 * </ul>
 */
class TabularExtractorTest {

    // -------------------------------------------------------------------------
    // Markdown tables — the primary path for QualityTools-style output
    // -------------------------------------------------------------------------

    @Test
    void parsesQualityToolsStyleMarkdownTable() {
        String text =
                """
                查询条件 - 季度：2026年1季度

                | 季度 | 部门 | 质量分(缺陷密度) |
                |--|--|--|
                | 2026年1季度 | 杭州开发一部 | 23.1 |
                | 2026年1季度 | 杭州开发二部 | 13.1 |
                | 2026年1季度 | 杭州开发三部 | 3.1 |
                | 2026年1季度 | 杭州开发四部 | 6.1 |
                | 2026年1季度 | 杭州开发五部 | 26.1 |

                说明：质量分越高表示缺陷密度越大，质量越差
                """;
        TabularData table = TabularExtractor.tryParse(text);
        assertNotNull(table, "should detect the table");
        assertEquals(List.of("季度", "部门", "质量分(缺陷密度)"), table.columns());
        assertEquals(5, table.rowCount());
        assertEquals(List.of("2026年1季度", "杭州开发一部", "23.1"), table.rowsForTest().get(0));
    }

    @Test
    void smallTablesBelowThresholdAreLeftInline() {
        // 3 data rows < MIN_DATA_ROWS — inline is cheaper for the LLM than dereferencing a file.
        String text =
                """
                | 部门 | 质量分 |
                |--|--|
                | 一部 | 23.1 |
                | 二部 | 13.1 |
                | 三部 | 3.1 |
                """;
        assertNull(TabularExtractor.tryParse(text), "3-row table must NOT be artifactized");
    }

    @Test
    void exactlyMinDataRowsTriggersArtifactization() {
        // Boundary check: exactly MIN_DATA_ROWS = 4 must trigger.
        String text =
                """
                | a | b |
                |--|--|
                | 1 | 2 |
                | 3 | 4 |
                | 5 | 6 |
                | 7 | 8 |
                """;
        TabularData table = TabularExtractor.tryParse(text);
        assertNotNull(table);
        assertEquals(4, table.rowCount());
    }

    @Test
    void prosePunctuatedWithPipesIsNotMistakenForTable() {
        // Narrative prose like "a | b | c" appears in summaries — must not trigger.
        String text = "数据如下：alice | bob | carol 已完成,david | eve | frank 待办。\n";
        assertNull(TabularExtractor.tryParse(text));
    }

    @Test
    void preservesColumnsEvenWithExtraLeadingAndTrailingWhitespace() {
        String text =
                """
                  | 季度 | 部门 |
                  |--|--|
                  | q1 | d1 |
                  | q2 | d2 |
                  | q3 | d3 |
                  | q4 | d4 |
                """;
        TabularData table = TabularExtractor.tryParse(text);
        assertNotNull(table);
        assertEquals(List.of("季度", "部门"), table.columns());
    }

    @Test
    void stopsCollectingAtMalformedContinuationRow() {
        // If the table breaks (different col count, blank line, prose) we stop. Whatever we
        // collected before counts. Here the table has 5 rows then noise → 5 rows captured.
        String text =
                """
                | a | b |
                |--|--|
                | 1 | 2 |
                | 3 | 4 |
                | 5 | 6 |
                | 7 | 8 |
                | 9 | 10 |
                | 11 |
                trailing prose ...
                """;
        TabularData table = TabularExtractor.tryParse(text);
        assertNotNull(table);
        assertEquals(5, table.rowCount());
    }

    // -------------------------------------------------------------------------
    // JSON array-of-objects — secondary detection path
    // -------------------------------------------------------------------------

    @Test
    void parsesJsonArrayOfHomogeneousObjects() {
        String text =
                "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},"
                        + "{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},"
                        + "{\"id\":5,\"name\":\"e\"}]";
        TabularData table = TabularExtractor.tryParse(text);
        assertNotNull(table);
        assertEquals(List.of("id", "name"), table.columns());
        assertEquals(5, table.rowCount());
        assertEquals(List.of("1", "a"), table.rowsForTest().get(0));
    }

    @Test
    void heterogeneousJsonArrayIsRejected() {
        // Different objects with different keys aren't tabular — bail out.
        String text = "[{\"a\":1},{\"a\":2,\"b\":3},{\"c\":4},{\"a\":5},{\"a\":6}]";
        assertNull(TabularExtractor.tryParse(text));
    }

    @Test
    void smallJsonArrayBelowThresholdIsLeftInline() {
        String text = "[{\"a\":1},{\"a\":2},{\"a\":3}]";
        assertNull(TabularExtractor.tryParse(text));
    }

    @Test
    void plainJsonArrayOfPrimitivesIsNotTreatedAsTable() {
        // [1,2,3,4,5] is a list, not a table — JSON_ARRAY_HEAD requires "[ {".
        assertNull(TabularExtractor.tryParse("[1, 2, 3, 4, 5, 6]"));
    }

    @Test
    void textCompletelyUnrelatedToTablesIsRejected() {
        assertNull(TabularExtractor.tryParse("Hello, world. Today is a good day."));
        assertNull(TabularExtractor.tryParse(""));
        assertNull(TabularExtractor.tryParse(null));
    }

    // -------------------------------------------------------------------------
    // CSV rendering — what the artifact actually contains
    // -------------------------------------------------------------------------

    @Test
    void csvRoundTripIncludesHeaderAndAllRows() {
        TabularData table =
                TabularExtractor.tryParse(
                        """
                        | 部门 | 质量分 |
                        |--|--|
                        | 一部 | 23.1 |
                        | 二部 | 13.1 |
                        | 三部 | 3.1 |
                        | 四部 | 6.1 |
                        """);
        assertNotNull(table);
        String csv = table.toCsv();
        String[] lines = csv.split("\n");
        assertEquals("部门,质量分", lines[0]);
        assertEquals(5, lines.length); // 1 header + 4 data
        assertEquals("一部,23.1", lines[1]);
    }

    @Test
    void csvEscapesCommasQuotesAndNewlines() {
        // Hand-roll a table whose cells need escaping. Real QualityTools never produces these
        // characters, but if a future tool does, the artifact must stay valid CSV.
        TabularData table =
                new TabularData(
                        List.of("a", "b"),
                        List.of(
                                List.of("with,comma", "plain"),
                                List.of("with\"quote", "another"),
                                List.of("multi\nline", "x"),
                                List.of("normal", "ok")));
        String csv = table.toCsv();
        // Comma-bearing cell must be quoted
        assertTrue(csv.contains("\"with,comma\""), csv);
        // Quote-bearing cell must be quoted and the inner quote doubled
        assertTrue(csv.contains("\"with\"\"quote\""), csv);
        // Newline-bearing cell must be quoted
        assertTrue(csv.contains("\"multi\nline\""), csv);
    }

    @Test
    void previewMarkdownTruncatesWithExplanation() {
        TabularData table =
                TabularExtractor.tryParse(
                        """
                        | a | b |
                        |--|--|
                        | 1 | 2 |
                        | 3 | 4 |
                        | 5 | 6 |
                        | 7 | 8 |
                        | 9 | 10 |
                        | 11 | 12 |
                        | 13 | 14 |
                        """);
        assertNotNull(table);
        String preview = table.previewMarkdown(3);
        assertTrue(preview.contains("| 1 | 2 |"), preview);
        assertTrue(preview.contains("| 5 | 6 |"), preview);
        // 4th data row should NOT appear in a top-3 preview
        assertTrue(!preview.contains("| 7 | 8 |"), preview);
        assertTrue(preview.contains("省略 4 行"), preview);
    }
}
