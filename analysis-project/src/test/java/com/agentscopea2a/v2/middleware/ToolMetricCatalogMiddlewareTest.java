package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolMetricCatalogMiddlewareTest {

    @Test
    void addsOnlyAStableTopicCatalogAndExplorationProtocol() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(new ToolRoutingCatalog(List.of(), Set.of("QI卡口", "交付治理"), Set.of("质量分", "达标率"), Set.of()));
        ToolMetricCatalogMiddleware middleware = new ToolMetricCatalogMiddleware(catalogService, 8000);

        String prompt = middleware.onSystemPrompt(null, null, "base").block();

        assertTrue(prompt.contains("<tool_metric_catalog>"));
        assertTrue(prompt.contains("可查询业务主题：QI卡口、交付治理"));
        assertTrue(prompt.contains("tool_index"));
        assertFalse(prompt.contains("sql_list"));
        assertFalse(prompt.contains("script_list"));
        assertFalse(prompt.contains("sql_template"));
    }

    @Test
    void degradesTheWholeCatalogWhenItExceedsTheConfiguredLimit() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(new ToolRoutingCatalog(List.of(), Set.of("QI卡口"), Set.of("质量分"), Set.of()));
        ToolMetricCatalogMiddleware middleware = new ToolMetricCatalogMiddleware(catalogService, 1);

        String prompt = middleware.onSystemPrompt(null, null, "base").block();

        assertTrue(prompt.contains("工具主题目录暂不可用，请勿猜测工具 ID。"));
        assertFalse(prompt.contains("可查询业务主题："));
    }
}
