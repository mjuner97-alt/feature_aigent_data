package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

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
            ToolRoutingCatalog catalog = catalogService.snapshot();
            Map<String, TreeSet<String>> topicToMetrics = new TreeMap<>();
            for (ToolRoutingMetadata tool : catalog.tools()) {
                for (String topic : tool.topicTags()) {
                    topicToMetrics.computeIfAbsent(topic, key -> new TreeSet<>()).addAll(tool.metricTags());
                }
            }
            StringBuilder block = new StringBuilder("<tool_metric_catalog>\n")
                    .append("可查询业务主题与指标（完整清单，目录外判断的唯一依据，判断时不需要调用任何工具）：\n");
            topicToMetrics.forEach((topic, metrics) ->
                    block.append("- ").append(topic).append("：").append(String.join("、", metrics)).append('\n'));
            block.append("\n</tool_metric_catalog>");
            if (block.length() > maxChars) {
                return renderTopicsOnly(catalog);
            }
            return block.toString();
        } catch (Exception ignored) {
            return "<tool_metric_catalog>\n工具主题目录暂不可用，请勿猜测工具 ID。\n</tool_metric_catalog>";
        }
    }

    private String renderTopicsOnly(ToolRoutingCatalog catalog) {
        String topics = String.join("、", catalog.topicTags().stream().sorted().toList());
        return "<tool_metric_catalog>\n"
                + "可查询业务主题：" + "[" + topics + "]" + "\n\n"
                + "</tool_metric_catalog>";
    }
}
