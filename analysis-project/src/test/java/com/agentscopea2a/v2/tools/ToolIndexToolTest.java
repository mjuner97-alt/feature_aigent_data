package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolIndexResponse;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolIndexToolTest {

    private static ToolRoutingCatalog catalog() {
        return new ToolRoutingCatalog(List.of(), Set.of("QI卡口"), Set.of("质量分"), Set.of());
    }

    @Test
    void delegatesStructuredFiltersWithoutAcceptingTheRawQuestion() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());

        Object result = tool.toolIndex(RuntimeContext.empty(), List.of("QI卡口"), List.of("质量分"), List.of(), List.of(), 10);
        assertTrue(result instanceof ToolIndexResponse);
        assertEquals(List.of("质量分"), ((ToolIndexResponse) result).filters().metricTags());
    }

    @Test
    void unknownMetricTagReturnsTerminalDirectiveInsteadOfSearchResults() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of("QI卡口"), List.of("总体方案评审未报备"), List.of(), List.of(), 10);
        assertTrue(result instanceof String);
        String directive = (String) result;
        assertTrue(directive.contains("总体方案评审未报备"));
        assertTrue(directive.contains("当前不支持该指标查询"));
        assertTrue(directive.contains("禁止再调用 tool_index"));
    }

    @Test
    void unknownTopicTagReturnsTerminalDirective() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of("总体方案评审未报备"), List.of(), List.of(), List.of(), 10);
        assertTrue(result instanceof String);
        assertTrue(((String) result).contains("当前不支持该指标查询"));
    }

    @Test
    void consecutiveEmptyQueriesTripCircuitBreaker() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());
        RuntimeContext ctx = RuntimeContext.empty();

        Object first = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of("不存在维度"), List.of(), 10);
        Object second = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of("不存在维度"), List.of(), 10);
        assertTrue(first instanceof ToolIndexResponse);
        assertTrue(second instanceof ToolIndexResponse);

        Object third = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of("不存在维度"), List.of(), 10);
        assertTrue(third instanceof String);
        assertTrue(((String) third).contains("停止继续查询"));
    }

    @Test
    void knownTagsMatchingCaseInsensitivelyBypassUnknownGate() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of(" qi卡口 "), List.of(" 质量分 "), List.of(), List.of(), 10);
        assertTrue(result instanceof ToolIndexResponse);
    }
}
