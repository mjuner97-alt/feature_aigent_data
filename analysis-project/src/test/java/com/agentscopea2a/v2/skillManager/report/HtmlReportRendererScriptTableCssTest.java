package com.agentscopea2a.v2.skillManager.report;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scriptTableCss 派生与 chat 展示边界注入
 * （docs/202611/脚本表格样式上移hooks层统一方案.md §4.2/§4.3）。
 */
class HtmlReportRendererScriptTableCssTest {

    private static final String CSS = HtmlReportRenderer.scriptTableCss();

    /** REPORT_CSS 是 private 常量，反射读取做同值断言（防 scriptTableCss :root 与报告 :root 漂移）。 */
    private static String reportCssSource() throws Exception {
        java.lang.reflect.Field f = HtmlReportRenderer.class.getDeclaredField("REPORT_CSS");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void derivedFromTableCssSingleSource() {
        assertTrue(CSS.contains(".unified-table table {"), "表格规则必须来自 TABLE_CSS 派生");
        assertTrue(CSS.contains("rgb(192, 0, 0)"), "红头保留");
        assertTrue(CSS.contains(".unified-table tbody tr.summary-row"), "语义类随派生带入");
        assertTrue(CSS.contains(".unified-table td.rowhead"));
        assertTrue(CSS.contains(".unified-table table .red"));
        assertFalse(CSS.contains("#report "), "作用域必须整体改名");
        assertFalse(CSS.contains("#report-toc"), "目录规则不得进片段");
        assertFalse(CSS.contains(".report-shell"), "报告外壳规则不得进片段");
    }

    @Test
    void tableCssVarsAllDefinedInScriptTableCss() throws Exception {
        // TABLE_CSS 用到的 --report-* 变量必须在 scriptTableCss 的 :root 里定义且与 REPORT_CSS 同值
        Matcher use = Pattern.compile("var\\((--report-[a-z-]+)").matcher(HtmlReportRenderer.TABLE_CSS);
        while (use.find()) {
            String var = use.group(1);
            assertNotNull(firstDefinition(CSS, var), "scriptTableCss 缺少变量定义: " + var);
            // --report-row-background 是 tr 级状态变量 (报告里逐行定义为斑马纹/hover 值),
            // 不是 :root 配置项, 不做同值断言
            if ("--report-row-background".equals(var)) continue;
            String reportValue = firstDefinition(reportCssSource(), var);
            String scriptValue = firstDefinition(CSS, var);
            assertEquals(reportValue, scriptValue, var + " 两处定义漂移");
        }
    }

    private static String firstDefinition(String css, String var) {
        Matcher m = Pattern.compile(Pattern.quote(var) + "\\s*:\\s*([^;]+);").matcher(css);
        return m.find() ? m.group(1).trim() : null;
    }

    @Test
    void injectIntoEveryHtmlFence() {
        String answer = "结论 A\n```html\n<table><tr><td>1</td></tr></table>\n```\n"
                + "结论 B\n```html\n<table><tr><td>2</td></tr></table>\n```";
        String out = HtmlReportRenderer.injectScriptTableCss(answer);
        assertEquals(2, count(out, "/* scriptTableCss"), "每个围栏独立 iframe, 须各注一份");
        assertTrue(out.contains("```html\n<style>"), "CSS 注在围栏开标记之后");
        assertEquals(2, count(out, "```html"), "围栏数量不变");
    }

    @Test
    void injectionIdempotent() {
        String answer = "```html\n<table><tr><td>1</td></tr></table>\n```";
        String once = HtmlReportRenderer.injectScriptTableCss(answer);
        assertEquals(once, HtmlReportRenderer.injectScriptTableCss(once), "重复注入幂等");
    }

    @Test
    void injectIntoBareCompleteDocHead() {
        String answer = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head>"
                + "<body><div class=\"unified-table\"><table><tr><td>1</td></tr></table></div></body></html>";
        String out = HtmlReportRenderer.injectScriptTableCss(answer);
        assertTrue(out.contains("/* scriptTableCss"));
        int headClose = out.toLowerCase().indexOf("</head>");
        int cssIdx = out.indexOf("/* scriptTableCss");
        assertTrue(cssIdx >= 0 && headClose > cssIdx, "CSS 必须在 </head> 之前");
    }

    @Test
    void noHtmlBlockUntouched() {
        String answer = "| 指标 | 值 |\n|---|---|\n| 总数 | 1 |\n```echarts\n{}\n```";
        assertEquals(answer, HtmlReportRenderer.injectScriptTableCss(answer));
        assertEquals("", HtmlReportRenderer.injectScriptTableCss(""));
        assertTrue(HtmlReportRenderer.injectScriptTableCss(null) == null);
    }

    private static int count(String s, String needle) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
