package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.presentation.PresentationRenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Map;

/** Generic entry point; user-maintained styles live in the GaussDB template registry. */
public class PresentationRenderTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PresentationRenderService service;

    public PresentationRenderTool(PresentationRenderService service) { this.service = service; }

    @Tool(name = "presentation_render", description = "执行预注册的 ECharts/HTML 模板。绑定 sql 数据源的模板只传 templateId + params；也可传 sql_registry_exec 返回的 resultRef，或用 variables 兼容小型内联数据。不要传 HTML/CSS/JavaScript/ECharts option。返回摘要、报告 URL 和可直接复制的 Markdown 链接 markdownLink。最终回答必须原样使用 markdownLink。")
    public String render(
            @ToolParam(name = "templateId", description = "展示模板 ID，例如 q2_1_by_dept_version_metrics/report-v1") String templateId,
            @ToolParam(name = "params", description = "模板绑定的注册 SQL 参数，例如 {\"dept\":\"杭州开发二部\",\"version\":\"2026年7月份版本\"}", required = false) Map<String, Object> params,
            @ToolParam(name = "resultRef", description = "可选，sql_registry_exec 返回的短期结构化结果引用；传此项时不重复查询", required = false) String resultRef,
            @ToolParam(name = "variables", description = "兼容模式：模板 variable_schema 声明的小型内联变量 JSON", required = false) String variables) {
        if (variables != null && !variables.isBlank()) {
            try { MAPPER.readTree(variables); } catch (Exception e) { throw new IllegalArgumentException("variables 必须是合法 JSON: " + e.getMessage(), e); }
        }
        PresentationRenderService.Result result = service.render(templateId, params, resultRef, variables);
        var output = MAPPER.createObjectNode()
                .put("reportId", result.reportId())
                .put("title", result.title())
                .put("url", result.url())
                .put("markdownLink", markdownLink(result.title(), result.url()))
                .put("expiresAt", result.expiresAt().toString());
        if (result.summary() != null && !result.summary().isMissingNode() && !result.summary().isEmpty()) {
            output.set("summary", result.summary());
        }
        return output.toString();
    }

    /** A ready-to-copy link keeps every Skill's final Markdown contract consistent. */
    private static String markdownLink(String title, String url) {
        String label = title == null || title.isBlank() ? "查看报告" : title;
        String target = url == null ? "" : url.replace("\\", "\\\\").replace(")", "\\)");
        return "[" + label.replace("\\", "\\\\").replace("]", "\\]") + "](" + target + ")";
    }
}
