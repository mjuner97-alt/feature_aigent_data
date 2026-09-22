package com.agentscopea2a.v2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownTableConverterTest {

    @Test
    void escapedPipeStaysInsideItsCell() {
        // 表生成方 (SqlRegistryExecTool.escapeCell) 把字段内字面竖线转义为 \|,
        // 切分必须按未转义 | 进行, 否则该行多出单元格, CSV 列错位.
        String md = String.join("\n",
                "[sql_registry_exec] sqlId=demo",
                "",
                "| 涉及部门 | issue_title |",
                "|---|---|",
                "| 杭州开发三部 | 正常标题 |",
                "| 杭州开发三部 | 标题含 \\| 竖线 |",
                "| 杭州开发三部 | 项目,逗号 |",
                "",
                "[sql_registry_exec] 共 3 行");

        String csv = MarkdownTableConverter.toCsv(md);

        assertEquals("涉及部门,issue_title\n"
                + "杭州开发三部,正常标题\n"
                + "杭州开发三部,标题含 | 竖线\n"
                + "杭州开发三部,\"项目,逗号\"", csv);
    }
}
