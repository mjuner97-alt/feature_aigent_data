package com.agentscopea2a.v2.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptExecOutputExtractorTest {

    @Test
    void extractsStdoutAndDropsExecutionMetadataAndStderr() {
        String output = """
                [script_exec] scriptId=q2_1_metrics_by_dept_version exit=0 elapsed=5923ms
                ─── stdout ─────────────────────────
                杭州开发二部 Q2-1 达标率为 100.00%。
                | 项目总数 | 达标率 |
                | 80 | 100.00% |
                ─── stderr ─────────────────────────
                INFO: Connection is established.
                """;

        assertEquals("""
                杭州开发二部 Q2-1 达标率为 100.00%。
                | 项目总数 | 达标率 |
                | 80 | 100.00% |""", ScriptExecOutputExtractor.extractStdout(output));
    }

    @Test
    void returnsOriginalTextWhenToolEnvelopeIsAbsent() {
        assertEquals("plain report", ScriptExecOutputExtractor.extractStdout("plain report"));
    }

    @Test
    void mapsExplicitEmptyStdoutToEmptyText() {
        String output = """
                [script_exec] scriptId=test exit=1 elapsed=1ms
                ─── stdout ─────────────────────────
                (空)
                ─── stderr ─────────────────────────
                failed
                """;

        assertEquals("", ScriptExecOutputExtractor.extractStdout(output));
    }

    @Test
    void extractsOnlyEchartsAndHtmlBlocks() {
        String output = """
                [script_exec] scriptId=test exit=0 elapsed=1ms
                ─── stdout ─────────────────────────
                结论：达标率为 100%。
                ```echarts
                {"series":[]}
                ```
                ```html
                <table><tr><td>100%</td></tr></table>
                ```
                ─── stderr ─────────────────────────
                INFO: connection
                """;

        assertEquals("""
                ```echarts
                {"series":[]}
                ```

                ```html
                <table><tr><td>100%</td></tr></table>
                ```""", ScriptExecOutputExtractor.extractRenderableBlocks(output));
    }

    @Test
    void returnsEmptyWhenStdoutHasNoRenderableBlocks() {
        String output = """
                [script_exec] scriptId=test exit=0 elapsed=1ms
                ─── stdout ─────────────────────────
                普通摘要和 Markdown 表格
                ─── stderr ─────────────────────────
                INFO: connection
                """;

        assertEquals("", ScriptExecOutputExtractor.extractRenderableBlocks(output));
    }

    @Test
    void detectsDownloadLinkLineInStdout() {
        String output = """
                [script_exec] scriptId=test exit=0 elapsed=1ms
                ─── stdout ─────────────────────────
                汇总表如下。
                📥 <!DOCTYPE html>
                <html>
                <head>
                    <title></title>
                </head>
                <body>
                <a href="http://localhost:18080/redirect/download?shortCode=abc123" target="_blank" rel="noreferrer" style="color:#6366f1;text-decoration:none">q2_1_明细.csv</a>
                </body>
                </html>
                ─── stderr ─────────────────────────
                INFO: connection
                """;

        String stdout = ScriptExecOutputExtractor.extractStdout(output);
        // 完整 HTML 文档形式的下载块同时命中可渲染块模式 (doctype 分支)
        assertTrue(ScriptExecOutputExtractor.extractRenderableBlocks(output)
                .contains("/redirect/download?shortCode=abc123"));
        assertEquals(true, ScriptExecOutputExtractor.stdoutHasDownloadLink(stdout));
    }

    @Test
    void downloadLinkDetectionIgnoresFailureLineAndOrdinaryText() {
        String output = """
                [script_exec] scriptId=test exit=0 elapsed=1ms
                ─── stdout ─────────────────────────
                📥 下载生成失败: too large (filename=明细.csv)
                详见 /redirect/download?shortCode= 文档说明
                ─── stderr ─────────────────────────
                INFO: connection
                """;

        assertEquals(false,
                ScriptExecOutputExtractor.stdoutHasDownloadLink(ScriptExecOutputExtractor.extractStdout(output)));
    }
}
