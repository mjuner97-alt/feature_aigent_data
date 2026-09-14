package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingToolType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolMetricCatalogMiddlewareTest {

    private static ToolRoutingMetadata apiTool(String toolId, List<String> topics, List<String> metrics) {
        return new ToolRoutingMetadata(toolId, ToolRoutingToolType.API, "desc", topics, metrics, List.of(), 0, true, null);
    }

    @Test
    void rendersFullCatalogWithPerTopicMetricLines() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(new ToolRoutingCatalog(
                List.of(apiTool("quality_query_by_department_quarter",
                        List.of("QI卡口", "交付治理"), List.of("质量分", "达标率"))),
                Set.of("QI卡口", "交付治理"), Set.of("质量分", "达标率"), Set.of()));
        ToolMetricCatalogMiddleware middleware = new ToolMetricCatalogMiddleware(catalogService, 8000);

        String prompt = middleware.onSystemPrompt(null, null, "base").block();

        assertTrue(prompt.contains("<tool_metric_catalog>"));
        assertTrue(prompt.contains("可查询业务主题与指标"));
        assertTrue(prompt.contains("- QI卡口：质量分、达标率"));
        assertTrue(prompt.contains("- 交付治理：质量分、达标率"));
        assertFalse(prompt.contains("sql_list"));
        assertFalse(prompt.contains("script_list"));
        assertFalse(prompt.contains("sql_template"));
    }

    @Test
    void degradesToBracketedTopicsOnlyWhenCatalogExceedsLimit() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(new ToolRoutingCatalog(
                List.of(apiTool("quality_query_by_department_quarter",
                        List.of("QI卡口"), List.of("质量分"))),
                Set.of("QI卡口"), Set.of("质量分"), Set.of()));
        ToolMetricCatalogMiddleware middleware = new ToolMetricCatalogMiddleware(catalogService, 1);

        String prompt = middleware.onSystemPrompt(null, null, "base").block();

        assertTrue(prompt.contains("可查询业务主题：[QI卡口]"));
        assertFalse(prompt.contains("可查询业务主题与指标"));
    }

    @Test
    void rendersFallbackWhenSnapshotThrows() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenThrow(new IllegalStateException("db down"));
        ToolMetricCatalogMiddleware middleware = new ToolMetricCatalogMiddleware(catalogService, 8000);

        String prompt = middleware.onSystemPrompt(null, null, "base").block();

        assertTrue(prompt.contains("工具主题目录暂不可用，请勿猜测工具 ID。"));
    }
}
