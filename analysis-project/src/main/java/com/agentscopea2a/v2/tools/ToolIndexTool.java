package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.toolrouting.ToolIndexRequest;
import com.agentscopea2a.v2.toolrouting.ToolIndexResponse;
import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;

/** LLM-facing deterministic discovery entry point for SQL, API, and script capabilities. */
public class ToolIndexTool {

    private final ToolRoutingCatalogService catalogService;
    private final ToolIndexService toolIndexService;
    public ToolIndexTool(ToolRoutingCatalogService catalogService, ToolIndexService toolIndexService) {
        this.catalogService = catalogService;
        this.toolIndexService = toolIndexService;
    }

    @Tool(name = "tool_index", description = "按规范业务主题、指标、维度和类型查询可执行 SQL、API、Python 脚本候选。"
            + "不接收用户原始问题，不做语义匹配。")
    public ToolIndexResponse toolIndex(
            @ToolParam(name = "topicTags", description = "必填：从业务主题目录选择的规范主题标签") List<String> topicTags,
            @ToolParam(name = "metricTags", description = "可选：首次主题查询后从 availableMetricTags 中选择") List<String> metricTags,
            @ToolParam(name = "dimensionTags", description = "可选：首次查询后从 availableDimensionTags 中选择") List<String> dimensionTags,
            @ToolParam(name = "toolTypes", description = "可选：SQL、API、SCRIPT") List<String> toolTypes,
            @ToolParam(name = "limit", description = "可选：返回条数，默认 10，最大 20", required = false) Integer limit) {
        return toolIndexService.index(catalogService.snapshot(),
                new ToolIndexRequest(topicTags, metricTags, dimensionTags, toolTypes, limit));
    }
}
