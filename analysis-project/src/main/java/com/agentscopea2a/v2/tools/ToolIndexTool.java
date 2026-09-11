package com.agentscopea2a.v2.tools;

import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.toolrouting.ToolIndexRequest;
import com.agentscopea2a.v2.toolrouting.ToolIndexResponse;
import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
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
 *       catalog returns a directive string instead of search results. Tag classification:
 *       routing metadata with enabled=true is an open, discoverable tool (usage directive - pass
 *       topic/metric tags, or execute directly if the ID+params came from a skill); routing
 *       metadata with enabled=false or an ID existing only in the SQL/script registry is a hidden
 *       skill-fixed ID (direct-execute directive - probing tool_index can never find it);
 *       anything else is out of catalog and the reply is the terminal "不支持该指标查询"
 *       (business rule is exact-match-only).</li>
 *   <li><b>Discovery-streak circuit breaker</b>: every call increments {@code discoveryStreak} on
 *       the RuntimeContext; {@link com.agentscopea2a.v2.middleware.DiscoveryStreakResetMiddleware}
 *       resets it when an executor tool is invoked. More than {@link #MAX_DISCOVERY_STREAK}
 *       consecutive tool_index calls without any executor in between returns a stop directive -
 *       the three-level protocol needs at most 3 calls, so longer streaks are enumerate-and-probe
 *       loops.</li>
 * </ol>
 */
public class ToolIndexTool {

    /** RuntimeContext key for consecutive discovery calls since the last executor invocation. */
    public static final String DISCOVERY_STREAK_KEY = "toolIndex.discoveryStreak";

    static final int MAX_DISCOVERY_STREAK = 3;

    private final ToolRoutingCatalogService catalogService;
    private final ToolIndexService toolIndexService;
    private final ToolRoutingMetadataRepository routingMetadataRepository;
    private final SqlRegistryMapper sqlRegistryMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;

    public ToolIndexTool(ToolRoutingCatalogService catalogService, ToolIndexService toolIndexService) {
        this(catalogService, toolIndexService, null, null, null);
    }

    public ToolIndexTool(ToolRoutingCatalogService catalogService, ToolIndexService toolIndexService,
                         ToolRoutingMetadataRepository routingMetadataRepository,
                         SqlRegistryMapper sqlRegistryMapper,
                         ScriptRegistryMapper scriptRegistryMapper) {
        this.catalogService = catalogService;
        this.toolIndexService = toolIndexService;
        this.routingMetadataRepository = routingMetadataRepository;
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
    }

    @Tool(name = "tool_index", description = "按规范业务主题、指标、维度和类型查询可执行 SQL、API、Python 脚本候选。"
            + "不接收用户原始问题，不做语义匹配。topicTags/metricTags 必须来自系统提示词 <tool_metric_catalog> 中的准确名称；"
            + "目录外标签会被直接拒绝并要求终止查询。"
            + "Skill 正文指定的固定 toolId/sqlId/scriptId 是隐藏工具，不通过本工具发现；参数未知用 toolMetaInfo 查询。")
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
            String openId = null;
            String hiddenId = null;
            for (String tag : unknown) {
                if (isOpenInRoutingMetadata(tag)) {
                    if (openId == null) {
                        openId = tag.trim();
                    }
                } else if (hiddenId == null && isRegisteredHiddenId(tag)) {
                    hiddenId = tag.trim();
                }
            }
            if (openId != null) {
                return openRoutedIdDirective(openId);
            }
            if (hiddenId != null) {
                return hiddenIdDirective(hiddenId);
            }
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

    /**
     * True if the tag is a tool ID registered in routing metadata with enabled=true - such tools
     * are discoverable through tool_index and must NOT be treated as hidden fixed IDs.
     */
    private boolean isOpenInRoutingMetadata(String tag) {
        String id = tag.trim();
        if (routingMetadataRepository == null || id.isEmpty()) {
            return false;
        }
        try {
            return routingMetadataRepository.findByToolId(id)
                    .map(ToolRoutingMetadata::enabled)
                    .orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * True if the tag matches a registered executable ID that is hidden from tool_index:
     * either routing metadata with enabled=false (staged rollout), or an ID that exists only in
     * the SQL / script registry and is not routed at all (skill-fixed IDs).
     */
    private boolean isRegisteredHiddenId(String tag) {
        String id = tag.trim();
        if (id.isEmpty()) {
            return false;
        }
        try {
            if (routingMetadataRepository != null) {
                if (routingMetadataRepository.findByToolId(id).isPresent()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // registry unavailable - fall through to the other registries
        }
        try {
            if (sqlRegistryMapper != null && sqlRegistryMapper.countBySqlId(id) > 0) {
                return true;
            }
        } catch (Exception ignored) {
            // registry unavailable
        }
        try {
            if (scriptRegistryMapper != null && scriptRegistryMapper.countByScriptId(id) > 0) {
                return true;
            }
        } catch (Exception ignored) {
            // registry unavailable
        }
        return false;
    }

    private static String openRoutedIdDirective(String id) {
        return "⛔ " + id + " 是 tool_index 可发现工具的 ID，不能作为 topicTags/metricTags 标签传入。\n"
                + "- 已从 Skill 正文或前序上下文获得该 toolId 和参数 -> 直接调用对应执行器："
                + "API -> router_tool(paramsJson={\"toolId\":\"" + id + "\",...})；"
                + "SQL -> sql_registry_exec(sqlId=\"" + id + "\", params=...)；"
                + "SCRIPT -> script_exec(scriptId=\"" + id + "\", params=...)。\n"
                + "- 未获得该工具 -> 用 <tool_metric_catalog> 中的主题/指标标签重新查询 tool_index。\n";
    }

    private static String hiddenIdDirective(String id) {
        return "⛔ " + id + " 是已注册工具的执行 ID（toolId / sqlId / scriptId），不在 tool_index 发现目录中——"
                + "Skill 指定的固定 ID 工具是隐藏的，本工具查不到，禁止再用 tool_index 查询或验证它。\n"
                + "- Skill 正文已给出该 ID 和参数 -> 直接调用对应执行器："
                + "SQL -> sql_registry_exec(sqlId=..., params=...)；"
                + "SCRIPT -> script_exec(scriptId=..., params=...)；"
                + "API -> router_tool(paramsJson={\"toolId\":...})。\n"
                + "- 参数定义未知 -> 用 toolMetaInfo(toolId=\"" + id + "\") 查询。\n";
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
