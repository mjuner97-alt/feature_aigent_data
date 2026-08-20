package com.agentscopea2a.v2.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
