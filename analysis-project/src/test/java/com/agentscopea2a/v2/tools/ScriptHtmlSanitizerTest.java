package com.agentscopea2a.v2.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScriptHtmlSanitizer v3: 剥 &lt;script&gt; + &lt;style&gt; 作用域重写 + 兜底皮肤 + 包壳 (方案 §4.1 v3 修订). */
class ScriptHtmlSanitizerTest {

    /** _html_table.py 旧版 (#report 作用域) 皮肤——存量脚本仍是这个形态. */
    private static final String LEGACY_REPORT_CSS =
            "<style>\n#report th { background: rgb(192, 0, 0) !important; color:#fff !important; }\n"
            + "#report td { color:#333; }\n#report tr:hover td { background:#fff3e0 !important; }\n</style>";

    @Test
    void fencedBlockStyleScopedAndWrapped() {
        String stdout = "汇总如下\n```html\n" + LEGACY_REPORT_CSS
                + "<table><tr><th>指标</th></tr></table>\n```\njson: {\"total\":1}";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("<style"), "样式保留: v3 作用域重写而非剥除");
        assertFalse(out.contains("#report"), "保留名 #report 选择器必须重写");
        assertTrue(out.contains(".unified-table th"), "选择器重写到 unified-table 作用域");
        assertFalse(out.contains(".unified-table .unified-table"), "#report 重写产物不得再加前缀(内网双重前缀bug)");
        assertEquals(1, count(out, "rgb(192, 0, 0)"), "自带皮肤 → 不注入兜底");
        assertTrue(out.contains("```html"), "围栏标记必须保留");
        assertTrue(out.contains("<div class=\"unified-table\">"));
        assertTrue(out.contains("<div class=\"table-wrap\">"));
        assertTrue(out.contains("<table>"), "表体保留");
        assertTrue(out.contains("json: {\"total\":1}"), "围栏外文本不动");
    }

    @Test
    void personalizationPreservedAndScoped() {
        String stdout = "```html\n<style>.mytbl td { font-weight:bold; }</style>"
                + "<div class=\"mytbl\"><table><tr><td>1</td></tr></table></div>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("font-weight:bold"), "个性化规则保留");
        assertTrue(out.contains(".unified-table .mytbl td"), "个性化规则重写到统一作用域");
        assertTrue(out.contains("rgb(192, 0, 0)"), "无自带皮肤 → 注入兜底皮肤");
        assertFalse(out.contains("#report"));
    }

    @Test
    void fencedBlockWithoutTableNoWrap() {
        String stdout = "```html\n" + LEGACY_REPORT_CSS + "<p>说明文字</p>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("#report"));
        assertFalse(out.contains("class=\"unified-table\""), "无表格不包壳");
        assertTrue(out.contains("<p>说明文字</p>"));
        assertTrue(out.contains(".unified-table th"), "样式仍被作用域重写");
    }

    @Test
    void fencedBlockScriptStripped() {
        String stdout = "```html\n<script>alert(1)</script>"
                + "<table><tr><td>1</td></tr></table>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("script"), "script 必须剥净");
        assertTrue(out.contains("<table>"));
        assertTrue(out.contains("rgb(192, 0, 0)"), "裸表注入兜底皮肤");
    }

    @Test
    void bareCompleteDocStyleScopedAndBodyWrapped() {
        String stdout = "<!DOCTYPE html>\n<html><head><style>body{background:#fff}</style>"
                + LEGACY_REPORT_CSS + "</head><body>\n<table><tr><td>x</td></tr></table>\n</body></html>";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("<style"));
        assertFalse(out.contains("#report"));
        assertFalse(out.contains("<script"));
        assertTrue(out.contains("<head>"), "文档结构保留");
        assertTrue(out.contains(".unified-table{background:#fff}"), "body 文档级选择器改挂统一壳");
        int bodyOpen = out.toLowerCase().indexOf("<body");
        int wrapper = out.indexOf("class=\"unified-table\"");
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
    void bareTableGetsBaseSkinAndIdempotent() {
        String once = ScriptHtmlSanitizer.sanitize(
                "```html\n<table><tr><td>1</td></tr></table>\n```");
        assertTrue(once.contains("rgb(192, 0, 0)"), "裸表注入兜底皮肤");
        assertTrue(once.contains("<div class=\"unified-table\">"));
        String twice = ScriptHtmlSanitizer.sanitize(once);
        assertEquals(once, twice, "重复调用幂等");
        assertEquals(1, count(once, "class=\"unified-table\""), "不产生双层壳");
        assertEquals(1, count(once, "rgb(192, 0, 0)"), "皮肤不重复注入");
    }

    @Test
    void downloadLinkLinesUntouched() {
        String stdout = "```\n📥 <a href=\"http://x/redirect/download?shortCode=abc\">明细.csv</a>\n```"
                + "\n```html\n<table><tr><td>1</td></tr></table>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("redirect/download?shortCode=abc"), "下载链接不动");
        assertTrue(out.contains("class=\"unified-table\""));
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
        // 期望: CSS 选择器重写到 .unified-table, id="report" 改名 class="unified-table",
        //       自带 table-wrap 不再外套第二个壳
        String stdout = "```html\n" + LEGACY_REPORT_CSS
                + "<div id=\"report\">\n<div class=\"table-wrap\">\n"
                + "<table><tr><td>1</td></tr></table>\n</div>\n</div>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains("id=\"report\""), "保留名 id 必须改名");
        assertFalse(out.contains("#report"));
        assertFalse(out.contains(".unified-table .unified-table"), "不得双重前缀");
        assertEquals(1, count(out, ".unified-table th"), "选择器精确重写一次");
        assertTrue(out.contains("<div class=\"unified-table\">"), "外壳挂靠点就位");
        assertEquals(1, count(out, "table-wrap"), "不得出现双重滚动壳");
        String twice = ScriptHtmlSanitizer.sanitize(out);
        assertEquals(out, twice, "归一幂等");
    }

    @Test
    void bareFragmentWrappedSkinnedAndScoped() {
        // 还原内网 wenshu_chadui_all 裸片段 stdout: json 信封 + guize/desc 样式 + 裸 to_html 表 + 下载文档
        String stdout = "{\"metricsChadui\":[\"chadui\"]}\n"
                + "<style>\n.guize { color: #999; margin: 10px 0; }\n"
                + ".desc { font-weight: bold; color: #333; }\n</style>\n"
                + "<div class=\"desc\"></div><div class=\"guize\">插队统计规则</div>"
                + "<table class='dataframe table table-bordered table-striped'>"
                + "<thead><tr><th>版本计划</th></tr></thead>"
                + "<tbody><tr><td>2026年12月份版本</td></tr></tbody></table>\n"
                + "<!DOCTYPE html>\n<html><head><title></title></head><body>\n"
                + "<a href=\"http://x/redirect/download?shortCode=abc\">明细.xlsx</a>\n"
                + "</body></html>";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertTrue(out.contains("<div class=\"unified-table\">"), "裸表包壳");
        assertTrue(out.contains("<div class=\"table-wrap\">"));
        assertTrue(out.contains("rgb(192, 0, 0)"), "无自带皮肤 → 注入兜底皮肤");
        assertTrue(out.contains(".unified-table .guize"), "个性化样式作用域重写且包壳后仍命中");
        assertTrue(out.contains("font-weight: bold"), "个性化声明保留");
        assertTrue(out.contains("{\"metricsChadui\":[\"chadui\"]}"), "json 信封不动");
        int jsonEnd = out.indexOf("{\"metricsChadui\"");
        int wrapper = out.indexOf("class=\"unified-table\"");
        assertTrue(jsonEnd < wrapper, "json 行在壳外");
        assertTrue(out.contains("redirect/download?shortCode=abc"), "下载链接原样");
        assertFalse(out.contains("<script"));
    }

    @Test
    void bareFragmentWithoutTableUntouched() {
        String stdout = "{\"total\":1}\n<style>.guize { color: #999; }</style>"
                + "<div class=\"guize\">说明文字</div>";
        assertEquals(stdout, ScriptHtmlSanitizer.sanitize(stdout));
    }

    @Test
    void bareFragmentIdempotent() {
        String stdout = "{\"metricsChadui\":[\"chadui\"]}\n"
                + "<style>.guize { color: #999; }</style>"
                + "<div class=\"guize\">规则</div>"
                + "<table class='dataframe'><tr><td>1</td></tr></table>";
        String once = ScriptHtmlSanitizer.sanitize(stdout);
        assertEquals(once, ScriptHtmlSanitizer.sanitize(once), "裸片段幂等");
        assertEquals(1, count(once, "class=\"unified-table\""), "不产生双层壳");
        assertEquals(1, count(once, "rgb(192, 0, 0)"), "皮肤不重复注入");
    }

    @Test
    void doubleScopeSelectorSelfHeal() {
        // 模拟内网已产出的脏 stdout (.unified-table .unified-table 双重前缀) 再过一遍 → 塌缩自愈
        String stdout = "```html\n<style>.unified-table .unified-table th"
                + "{ background: rgb(192, 0, 0) !important; }</style>"
                + "<div class=\"unified-table\"><table><tr><th>指标</th></tr></table></div>\n```";
        String out = ScriptHtmlSanitizer.sanitize(stdout);
        assertFalse(out.contains(".unified-table .unified-table"), "双重前缀塌缩为单前缀");
        assertTrue(out.contains(".unified-table th{"), "选择器自愈");
        String twice = ScriptHtmlSanitizer.sanitize(out);
        assertEquals(out, twice, "自愈后幂等");
    }

    @Test
    void reportSelectorExactRewrite() {
        String css = "<style>\n#report { padding:16px; }\n#report .table-wrap { overflow-x:auto; }\n"
                + "#report td.red, #report .red { color:red !important; }\n</style>";
        String out = ScriptHtmlSanitizer.rewriteRules(css);
        assertTrue(out.contains(".unified-table{ padding:16px; }"), out);
        assertTrue(out.contains(".unified-table .table-wrap{ overflow-x:auto; }"), out);
        assertTrue(out.contains(".unified-table td.red, .unified-table .red{ color:red !important; }"), out);
        assertFalse(out.contains("#report"));
        assertFalse(out.contains(".unified-table .unified-table"));
    }

    private static int count(String s, String needle) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
