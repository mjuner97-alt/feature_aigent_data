package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.toolrouting.ToolIndexRequest;
import com.agentscopea2a.v2.toolrouting.ToolIndexResponse;
import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM-facing deterministic discovery entry point for SQL, API, and script capabilities.
 *
 * <p>Hard guards for weak models that ignore prompt-level discipline:
 * <ol>
 *   <li><b>Unknown-tag gate (fail-closed)</b>: any requested topic/metric tag that is not in the
 *       catalog returns a terminal directive string instead of search results. Business rule is
 *       exact-match-only, so an out-of-catalog tag means the user's metric is not registered -
 *       probing with other tags cannot change that.</li>
 *   <li><b>Discovery-streak circuit breaker</b>: every call increments {@code discoveryStreak} on
 *       the RuntimeContext; {@link com.agentscopea2a.v2.middleware.DiscoveryStreakResetMiddleware}
 *       resets it when an executor tool is invoked. More than {@link #MAX_DISCOVERY_STREAK}
 *       consecutive tool_index calls without any executor in between returns a stop directive -
 *       the three-level protocol needs at most 3 calls, so longer streaks are enumerate-and-probe
 *       loops (observed on qwen: model judged the metric out-of-catalog, then drilled valid topic
 *       tags one by one for 5+ calls / several minutes).</li>
 * </ol>
 */
public class ToolIndexTool {

    /** RuntimeContext key for consecutive discovery calls since the last executor invocation. */
    public static final String DISCOVERY_STREAK_KEY = "toolIndex.discoveryStreak";

    static final int MAX_DISCOVERY_STREAK = 3;

    private final ToolRoutingCatalogService catalogService;
    private final ToolIndexService toolIndexService;

    public ToolIndexTool(ToolRoutingCatalogService catalogService, ToolIndexService toolIndexService) {
        this.catalogService = catalogService;
        this.toolIndexService = toolIndexService;
    }

    @Tool(name = "tool_index", description = "按规范业务主题、指标、维度和类型查询可执行 SQL、API、Python 脚本候选。"
            + "不接收用户原始问题，不做语义匹配。topicTags/metricTags 必须来自系统提示词 <tool_metric_catalog> 中的准确名称；"
            + "目录外标签会被直接拒绝并要求终止查询。")
    public Object toolIndex(
            RuntimeContext runtimeContext,
            @ToolParam(name = "topicTags", description = "必填：从业务主题目录选择的规范主题标签") List<String> topicTags,
            @ToolParam(name = "metricTags", description = "可选：首次主题查询后从 availableMetricTags 中选择") List<String> metricTags,
            @ToolParam(name = "dimensionTags", description = "可选：首次查询后从 availableDimensionTags 中选择") List<String> dimensionTags,
            @ToolParam(name = "toolTypes", description = "可选：SQL、API、SCRIPT") List<String> toolTypes,
            @ToolParam(name = "limit", description = "可选：返回条数，默认 10，最大 20", required = false) Integer limit) {
        ToolRoutingCatalog catalog = catalogService.snapshot();

        List<String> unknown = new ArrayList<>();
        unknown.addAll(unknownTags(topicTags, catalog.topicTags()));
        unknown.addAll(unknownTags(metricTags, catalog.metricTags()));
        if (!unknown.isEmpty()) {
            return "⛔ 请求的标签 " + unknown + " 不在 <tool_metric_catalog> 可查询业务主题与指标清单内。\n"
                    + "目录外指标判定为终局：本工具不会为目录外标签返回任何候选，禁止再调用 tool_index、"
                    + "改换其他标签重试、或调用 toolMetaInfo 及任何执行工具。\n"
                    + "立即停止所有工具调用，直接回复用户：「当前不支持该指标查询，建议业务方补充注册该指标」。\n";
        }

        int streak = 1;
        if (runtimeContext != null) {
            Integer current = runtimeContext.get(DISCOVERY_STREAK_KEY, Integer.class);
            streak = (current == null ? 0 : current) + 1;
            runtimeContext.put(DISCOVERY_STREAK_KEY, streak);
        }
        if (streak > MAX_DISCOVERY_STREAK) {
            return "⛔ 已连续 " + streak + " 次调用 tool_index 且期间未调用任何执行工具。禁止继续发现查询。\n"
                    + "若此前查询已返回候选工具，立即选定 toolId 并调用执行器 (router_tool / sql_registry_exec / script_exec)；\n"
                    + "若用户请求的指标不在 <tool_metric_catalog> 目录内，立即停止所有工具调用，"
                    + "直接回复用户：「当前不支持该指标查询，建议业务方补充注册该指标」。\n";
        }

        return toolIndexService.index(catalog,
                new ToolIndexRequest(topicTags, metricTags, dimensionTags, toolTypes, limit));
    }

    private static List<String> unknownTags(List<String> requested, java.util.Set<String> known) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (String tag : requested) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            boolean matched = known.stream().anyMatch(k -> normalize(k).equals(normalize(tag)));
            if (!matched) {
                unknown.add(tag.trim());
            }
        }
        return unknown;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
