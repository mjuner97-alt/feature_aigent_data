package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.service.DownloadContentService;
import com.agentscopea2a.v2.service.MarkdownTableConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptExecToolTest {

    /** 桩: 不落库, 固定 shortCode, 记录 create 调用入参. */
    private static class FakeDownloadContentService extends DownloadContentService {
        record Call(String content, String filename, String mimeType) {}
        final List<Call> calls = new ArrayList<>();

        FakeDownloadContentService() {
            super(null, "");
        }

        @Override
        public String create(String content, String filename, String mimeType) {
            calls.add(new Call(content, filename, mimeType));
            return "abc123DEF456";
        }

        @Override
        public String buildDownloadUrl(String shortCode) {
            return "/redirect/download?shortCode=" + shortCode;
        }
    }

    private ScriptExecTool newTool(FakeDownloadContentService downloadService) {
        return new ScriptExecTool(null, null, null, null, "ws", null, null, downloadService);
    }

    private String extractDownloads(ScriptExecTool tool, String stdout) throws Exception {
        Method m = ScriptExecTool.class.getDeclaredMethod("extractDownloads", String.class);
        m.setAccessible(true);
        return (String) m.invoke(tool, stdout);
    }

    @Test
    void replacesDownloadBlockWithLinkAtOriginalPosition() throws Exception {
        FakeDownloadContentService dl = new FakeDownloadContentService();
        ScriptExecTool tool = newTool(dl);
        String stdout = String.join("\n",
                "| 总数 | 已打分 |",
                "|---:|---:|",
                "| 80 | 70 |",
                "json: {\"total\":80}",
                "<<<DOWNLOAD_META>>> {\"filename\":\"q2_1_明细.csv\"}",
                "<<<DOWNLOAD_CONTENT>>>",
                "| 项目编号 | 项目名称 |",
                "|---|---|",
                "| P001 | 项目一 |",
                "| P002 | 项目二 |",
                "<<<DOWNLOAD_END>>>");

        String result = extractDownloads(tool, stdout);

        String expected = String.join("\n",
                "| 总数 | 已打分 |",
                "|---:|---:|",
                "| 80 | 70 |",
                "json: {\"total\":80}",
                "📥 <a href=\"/redirect/download?shortCode=abc123DEF456\" target=\"_blank\" rel=\"noreferrer\" style=\"color:#6366f1;text-decoration:none\">q2_1_明细.csv</a>");
        assertEquals(expected, result);
        // 块内明细内容不进工具结果
        assertTrue(!result.contains("P001") && !result.contains("<<<DOWNLOAD"), "块内内容须被剥离");
        assertEquals(1, dl.calls.size());
        assertTrue(dl.calls.get(0).content().contains("P001"));
        assertEquals("q2_1_明细.csv", dl.calls.get(0).filename());
        assertEquals(null, dl.calls.get(0).mimeType());
    }

    @Test
    void supportsMultipleDownloadBlocksInOrder() throws Exception {
        FakeDownloadContentService dl = new FakeDownloadContentService();
        ScriptExecTool tool = newTool(dl);
        String stdout = String.join("\n",
                "```echarts",
                "{\"series\":[]}",
                "```",
                "<<<DOWNLOAD_META>>> {\"filename\":\"明细.csv\"}",
                "<<<DOWNLOAD_CONTENT>>>",
                "a,b",
                "<<<DOWNLOAD_END>>>",
                "中间说明文字",
                "<<<DOWNLOAD_META>>> {\"filename\":\"报告.md\",\"mimeType\":\"text/markdown\"}",
                "<<<DOWNLOAD_CONTENT>>>",
                "# 报告标题",
                "<<<DOWNLOAD_END>>>");

        String result = extractDownloads(tool, stdout);

        String expected = String.join("\n",
                "```echarts",
                "{\"series\":[]}",
                "```",
                "📥 <a href=\"/redirect/download?shortCode=abc123DEF456\" target=\"_blank\" rel=\"noreferrer\" style=\"color:#6366f1;text-decoration:none\">明细.csv</a>",
                "中间说明文字",
                "📥 <a href=\"/redirect/download?shortCode=abc123DEF456\" target=\"_blank\" rel=\"noreferrer\" style=\"color:#6366f1;text-decoration:none\">报告.md</a>");
        assertEquals(expected, result);
        assertEquals(2, dl.calls.size());
        assertEquals("text/markdown", dl.calls.get(1).mimeType());
        assertEquals("明细.csv", dl.calls.get(0).filename());
    }

    @Test
    void keepsOriginalTextWhenEndMarkerMissing() throws Exception {
        FakeDownloadContentService dl = new FakeDownloadContentService();
        ScriptExecTool tool = newTool(dl);
        String stdout = String.join("\n",
                "汇总行",
                "<<<DOWNLOAD_META>>> {\"filename\":\"明细.csv\"}",
                "<<<DOWNLOAD_CONTENT>>>",
                "未闭合的块内容");

        String result = extractDownloads(tool, stdout);

        assertEquals(stdout, result);
        assertTrue(dl.calls.isEmpty());
    }

    @Test
    void keepsOriginalTextWhenMetaJsonInvalid() throws Exception {
        FakeDownloadContentService dl = new FakeDownloadContentService();
        ScriptExecTool tool = newTool(dl);
        String stdout = String.join("\n",
                "汇总行",
                "<<<DOWNLOAD_META>>> not-a-json",
                "<<<DOWNLOAD_CONTENT>>>",
                "数据行",
                "<<<DOWNLOAD_END>>>",
                "结尾");

        String result = extractDownloads(tool, stdout);

        assertEquals(stdout, result);
        assertTrue(dl.calls.isEmpty());
    }

    @Test
    void passesThroughStdoutWithoutDownloadBlocks() throws Exception {
        FakeDownloadContentService dl = new FakeDownloadContentService();
        ScriptExecTool tool = newTool(dl);
        String stdout = "| a | b |\n|---|---|\n| 1 | 2 |\njson: {}";

        String result = extractDownloads(tool, stdout);

        assertEquals(stdout, result);
        assertTrue(dl.calls.isEmpty());
    }

    @Test
    void reportsErrorWhenDownloadCreateFails() throws Exception {
        DownloadContentService failing = new DownloadContentService(null, "") {
            @Override
            public String create(String content, String filename, String mimeType) {
                throw new IllegalArgumentException("content 超过 5MB 上限: 6000000 bytes");
            }
        };
        ScriptExecTool tool = new ScriptExecTool(
                null, null, null, null, "ws", null, null, failing);
        String stdout = String.join("\n",
                "汇总行",
                "<<<DOWNLOAD_META>>> {\"filename\":\"big.csv\"}",
                "<<<DOWNLOAD_CONTENT>>>",
                "x",
                "<<<DOWNLOAD_END>>>");

        String result = extractDownloads(tool, stdout);

        assertTrue(result.contains("📥 下载生成失败: content 超过 5MB 上限"));
        assertTrue(result.contains("filename=big.csv"));
        assertTrue(!result.contains("<<<DOWNLOAD"));
    }

    @Test
    void convertsScriptMarkdownTableToCsv() {
        // plan §八-2: script 产 markdown 表 (不含 [sql_registry_exec] 头) -> CSV 转换路径
        String scriptTable = String.join("\n",
                "| 项目编号 | 项目名称 |",
                "|---|---|",
                "| P001 | 项目一 |",
                "| P002 | 项目,二 |");
        String csv = MarkdownTableConverter.toCsv(scriptTable);
        assertEquals("项目编号,项目名称\nP001,项目一\nP002,\"项目,二\"", csv);
    }
}
