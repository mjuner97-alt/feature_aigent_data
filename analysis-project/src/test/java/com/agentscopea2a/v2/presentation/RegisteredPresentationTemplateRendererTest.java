package com.agentscopea2a.v2.presentation;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.v2.skillManager.report.HtmlReportRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredPresentationTemplateRendererTest {
    private RegisteredPresentationTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer();
        htmlRenderer.init();
        renderer = new RegisteredPresentationTemplateRenderer(htmlRenderer);
    }

    @Test
    void rendersCombinedTemplateWithTypedChartBindingsAndHtmlLoop() {
        PresentationTemplateEntry template = template(
                "{\"title\":{\"text\":\"{{title}}\"},\"xAxis\":{\"data\":\"{{versions}}\"},\"series\":[{\"type\":\"line\",\"data\":\"{{rates}}\"}]}",
                "<!DOCTYPE html><html><head><style>th{background:#c00000;color:#fff}</style></head><body>{{@echarts}}<table><tbody>{{#records}}<tr><td>{{department}}</td><td>{{value}}</td></tr>{{/records}}</tbody></table></body></html>",
                "[{\"name\":\"title\",\"type\":\"string\",\"required\":true},{\"name\":\"versions\",\"type\":\"array\",\"required\":true},{\"name\":\"rates\",\"type\":\"array\",\"required\":true},{\"name\":\"records\",\"type\":\"array\",\"required\":true}]"
        );

        String html = renderer.render(template, """
                {"title":"趋势图","versions":["26年7月版"],"rates":[93.33],
                 "records":[{"department":"研发<一部>","value":"93.33%"}]}
                """).html();

        assertTrue(html.contains("echarts.init"));
        assertTrue(html.contains("\"data\" : [ \"26年7月版\" ]"));
        assertTrue(html.contains("\"data\" : [ 93.33 ]"));
        assertTrue(html.contains("研发&lt;一部&gt;"));
        assertTrue(html.contains("background:#c00000"));
    }

    @Test
    void supportsEchartsOnlyAndHtmlOnlyTemplates() {
        PresentationTemplateEntry chartOnly = template(
                "{\"xAxis\":{\"data\":\"{{labels}}\"},\"series\":[{\"data\":\"{{values}}\"}]}",
                null,
                "[{\"name\":\"labels\",\"type\":\"array\",\"required\":true},{\"name\":\"values\",\"type\":\"array\",\"required\":true}]"
        );
        String chartHtml = renderer.render(chartOnly, "{\"labels\":[\"A\"],\"values\":[1]}").html();
        assertTrue(chartHtml.contains("echarts.init"));

        PresentationTemplateEntry htmlOnly = template(
                null,
                "<section><h2>{{heading}}</h2></section>",
                "[{\"name\":\"heading\",\"type\":\"string\",\"required\":true}]"
        );
        String plainHtml = renderer.render(htmlOnly, "{\"heading\":\"HTML 报告\"}").html();
        assertTrue(plainHtml.contains("<h2>HTML 报告</h2>"));
    }

    @Test
    void rejectsUnknownVariablesMissingRequiredValuesAndUnsafeHtml() {
        PresentationTemplateEntry template = template(
                "{\"series\":[{\"data\":\"{{values}}\"}]}",
                null,
                "[{\"name\":\"values\",\"type\":\"array\",\"required\":true}]"
        );
        assertThrows(IllegalArgumentException.class, () -> renderer.render(template, "{\"values\":[1],\"extra\":2}"));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(template, "{}"));

        PresentationTemplateEntry unsafe = template(null, "<img src=x onerror=alert(1)>", "[]");
        assertThrows(IllegalArgumentException.class, () -> renderer.render(unsafe, "{}"));
    }

    @Test
    void rendersQ21SeedTemplateFromFlywayMigration() throws Exception {
        String resource = "/db/migration/gauss/V20260818.1__presentation_template_registry.sql";
        String sql;
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing test resource: " + resource);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        PresentationTemplateEntry seed = template(
                tagged(sql, "echarts"),
                tagged(sql, "html"),
                tagged(sql, "schema")
        );

        String html = renderer.render(seed, """
                {
                  "title":"Q2-1 打分率与达标率趋势",
                  "versions":["26年7月版"],
                  "scoredRates":[0.0],
                  "passedRates":[100.0],
                  "records":[{
                    "department":"杭州开发二部","version":"2026年7月份版本",
                    "total":80,"scored":0,"passed":80,
                    "scoredPctText":"0.00%","passedPctText":"100.00%"
                  }],
                  "dataSource":"GaussDB remote_app.dsqa_dwd_req_item_app_portrait_wide_inf"
                }
                """).html();

        assertTrue(html.contains("杭州开发二部"));
        assertTrue(html.contains("100.00%"));
        assertTrue(html.contains("#2563EB"));
        assertTrue(html.contains("#16803A"));
        assertTrue(html.contains("table.q21-table thead th{background:#c00000"));
    }

    private static String tagged(String sql, String tag) {
        Matcher matcher = Pattern.compile("\\$" + tag + "\\$([\\s\\S]*?)\\$" + tag + "\\$").matcher(sql);
        if (!matcher.find()) throw new IllegalArgumentException("missing $" + tag + "$ block");
        return matcher.group(1).trim();
    }

    private static PresentationTemplateEntry template(String echarts, String html, String schema) {
        return PresentationTemplateEntry.builder()
                .templateId("test/report-v1")
                .name("测试报告")
                .echartsTemplate(echarts)
                .htmlTemplate(html)
                .variableSchema(schema)
                .enabled(1)
                .build();
    }
}
