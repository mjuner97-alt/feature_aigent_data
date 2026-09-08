package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/** Adds the compact, dynamic topic catalog needed to begin deterministic tool discovery. */
public class ToolMetricCatalogMiddleware implements MiddlewareBase {

    private final ToolRoutingCatalogService catalogService;
    private final int maxChars;

    public ToolMetricCatalogMiddleware(ToolRoutingCatalogService catalogService, int maxChars) {
        this.catalogService = catalogService;
        this.maxChars = Math.max(1, maxChars);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> systemPrompt + "\n\n" + renderCatalog());
    }

    private String renderCatalog() {
        try {
            String topics = catalogService.snapshot().topicTags().stream().sorted()
                    .collect(java.util.stream.Collectors.joining("、"));
            String block = "<tool_metric_catalog>\n"
                    + "可查询业务主题：" + topics + "\n\n"
                    + "工具探索规则：\n"
                    + "1. 先将用户请求主题与上述目录精确匹配；目录外主题直接回复‘当前不支持该指标查询’，不得调用任何路由或执行工具。\n"
                    + "2. 主题在目录内时，首次选择 topicTags 调用 tool_index，只读取 availableMetricTags。\n"
                    + "3. 第二次只能从 availableMetricTags 选择 metricTags；读取候选和 availableDimensionTags。\n"
                    + "4. 需要收窄时，只能从 availableDimensionTags 选择 dimensionTags 再查。\n"
                    + "5. 根据候选选择工具后调用 toolMetaInfo 获取参数，并按 executeWith 调用执行入口。\n"
                    + "6. 不得编造 toolId，也不得请求全量工具列表。\n"
                    + "7. 本目录存在时，原子工具发现只使用 tool_index。\n"
                    + "</tool_metric_catalog>";
            if (block.length() > maxChars) {
                return "<tool_metric_catalog>\n工具主题目录暂不可用，请勿猜测工具 ID。\n</tool_metric_catalog>";
            }
            return block;
        } catch (Exception ignored) {
            return "<tool_metric_catalog>\n工具主题目录暂不可用，请勿猜测工具 ID。\n</tool_metric_catalog>";
        }
    }
}
