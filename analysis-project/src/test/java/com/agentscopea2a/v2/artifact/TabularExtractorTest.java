package com.agentscopea2a.v2.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TabularExtractorTest {

    @Test
    void escapedPipeInCellDoesNotTruncateTable() {
        // SqlRegistryExecTool.escapeCell renders a literal pipe inside a cell as "\|".
        // The splitter must treat it as cell content, not a delimiter — otherwise the row
        // over-splits, its column count diverges from the header, and parseMarkdownTable
        // breaks early, silently dropping every row after it (observed: 86 rows -> 42).
        String md = """
                [sql_registry_exec] sqlId=demo params={}

                | 涉及部门 | issue_title |
                |---|---|
                | 杭州开发三部 | 正常标题 |
                | 杭州开发三部 | 标题含 \\| 竖线 |
                | 杭州开发三部 | 后续行不能丢 |
                | 杭州开发三部 | 最后一行 |
                """;

        TabularExtractor.TabularData table = TabularExtractor.tryParse(md);

        assertNotNull(table);
        assertEquals(4, table.rowCount());
        assertEquals("标题含 | 竖线", table.rowsForTest().get(1).get(1));
        assertEquals("最后一行", table.rowsForTest().get(3).get(1));
    }

    @Test
    void csvRoundTripsUnescapedPipeValue() {
        String md = """
                | a | b |
                |---|---|
                | 1 | x \\| y |
                | 2 | plain |
                | 3 | plain |
                | 4 | x \\| y |
                """;
        TabularExtractor.TabularData table = TabularExtractor.tryParse(md);

        assertNotNull(table);
        String[] csvRows = table.toCsv().split("\n", -1);
        assertEquals("a,b", csvRows[0]);
        assertEquals("1,x | y", csvRows[1]);
        assertEquals("4,x | y", csvRows[4]);
    }
}
