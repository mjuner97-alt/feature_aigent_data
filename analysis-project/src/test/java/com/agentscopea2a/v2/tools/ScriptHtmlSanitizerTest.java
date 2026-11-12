package com.agentscopea2a.v2.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScriptHtmlSanitizer: 剥 &lt;style&gt;/&lt;script&gt; + 包 unified-table 壳 (方案 §4.1). */
class ScriptHtmlSanitizerTest {

    private static final String UNIFIED_CSS =
            "<style>.unified-table th { background: rgb(192, 0, 0) !important; }</style>";

    @Test
    void fencedBlockStyleStrippedAndWrapped() {
        String stdout = "汇总如下\n```html\n" + UNIFIED_CSS
                + "<table><tr><th>指标</th></tr></table>\n```\njson: {\"total\":1}";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("<style"), "样式必须剥净");
        assertFalse(out.contains("192, 0, 0"), "CSS 内容不得残留");
        assertTrue(out.contains("```html"), "围栏标记必须保留");
        assertTrue(out.contains("<div class=\"unified-table\">"));
        assertTrue(out.contains("<div class=\"table-wrap\">"));
        assertTrue(out.contains("<table>"), "表体保留");
        assertTrue(out.contains("json: {\"total\":1}"), "围栏外文本不动");
    }

    @Test
    void fencedBlockWithoutTableUntouchedExceptStyle() {
        String stdout = "```html\n" + UNIFIED_CSS + "<p>说明文字</p>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("<style"));
        assertFalse(out.contains("unified-table"), "无表格不包壳");
        assertTrue(out.contains("<p>说明文字</p>"));
    }

    @Test
    void fencedBlockScriptStripped() {
        String stdout = "```html\n<script>alert(1)</script>"
                + "<table><tr><td>1</td></tr></table>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("script"), "script 必须剥净");
        assertTrue(out.contains("<table>"));
    }

    @Test
    void bareCompleteDocStyleStrippedAndBodyWrapped() {
        String stdout = "<!DOCTYPE html>\n<html><head><style>body{background:#fff}</style>"
                + "</head><body>\n" + UNIFIED_CSS
                + "<table><tr><td>x</td></tr></table>\n</body></html>";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("<style"));
        assertFalse(out.contains("<script"));
        assertTrue(out.contains("<head>"), "文档结构保留");
        int bodyOpen = out.toLowerCase().indexOf("<body");
        int wrapper = out.indexOf("unified-table");
        int table = out.indexOf("<table>");
        assertTrue(bodyOpen >= 0 && wrapper > bodyOpen && table > wrapper,
                "壳必须包在 body 内容外、表格上");
        assertTrue(out.trim().endsWith("</html>"));
    }

    @Test
    void echartsAndPlainTextUntouched() {
        String stdout = "```echarts\n{\"series\":[]}\n```\n普通 markdown 文本";
        assertEquals(stdout, ScriptHtmlSanitizer.sanitize(stdout));
    }

    @Test
    void alreadyWrappedIsIdempotent() {
        String once = ScriptHtmlSanitizer.sanitize(
                "```html\n<table><tr><td>1</td></tr></table>\n```");
        String twice = ScriptHtmlSanitizer.sanitize(once);
        assertEquals(once, twice, "重复调用幂等");
        // 已包壳的块再跑一遍不产生双层壳
        assertEquals(1, count(once, "unified-table"));
    }

    @Test
    void downloadLinkLinesUntouched() {
        String stdout = "```\n📥 <a href=\"http://x/redirect/download?shortCode=abc\">明细.csv</a>\n```"
                + "\n```html\n<table><tr><td>1</td></tr></table>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("redirect/download?shortCode=abc"), "下载链接不动");
        assertTrue(out.contains("unified-table"));
    }

    @Test
    void blankAndNullPassthrough() {
        assertEquals(null, ScriptHtmlSanitizer.sanitize(null));
        assertEquals("", ScriptHtmlSanitizer.sanitize(""));
        assertEquals("纯文本", ScriptHtmlSanitizer.sanitize("纯文本"));
    }

    @Test
    void pythonLegacyIdReportWrapperNormalized() {
        // _html_table.py 现版外壳: <style> + <div id="report"><div class="table-wrap"><table>…
        // 期望: 样式剥净, id="report" 改名 unified-table (不产生嵌套重复 id),
        //       自带 table-wrap 不再外套第二个壳
        String stdout = "```html\n" + UNIFIED_CSS
                + "<div id=\"report\">\n<div class=\"table-wrap\">\n"
                + "<table><tr><td>1</td></tr></table>\n</div>\n</div>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("id=\"report\""), "保留名 id 必须改名");
        assertFalse(out.contains("<style"));
        assertTrue(out.contains("<div class=\"unified-table\">"), "外壳挂靠点就位");
        assertEquals(1, count(out, "table-wrap"), "不得出现双重滚动壳");
        assertEquals(1, count(out, "unified-table"));
        String twice = ScriptHtmlSanitizer.sanitize(out);
        assertEquals(out, twice, "归一幂等");
    }

    private static int count(String s, String needle) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
