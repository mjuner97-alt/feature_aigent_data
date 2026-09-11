package com.agentscopea2a.v2.tools;

import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolIndexResponse;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import com.agentscopea2a.v2.toolrouting.ToolRoutingToolType;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
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
    void unknownTagMatchingRegisteredSqlIdReturnsDirectExecutionDirective() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());
        SqlRegistryMapper sqlRegistryMapper = mock(SqlRegistryMapper.class);
        when(sqlRegistryMapper.countBySqlId("q2_1_metrics_by_dept_version")).thenReturn(1);

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService(),
                null, sqlRegistryMapper, null);

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of("质量"), List.of("q2_1_metrics_by_dept_version"), List.of(), List.of(), 10);
        assertTrue(result instanceof String);
        String directive = (String) result;
        assertTrue(directive.contains("q2_1_metrics_by_dept_version"));
        assertTrue(directive.contains("sql_registry_exec(sqlId="));
        assertTrue(directive.contains("禁止再用 tool_index 查询或验证"));
    }

    @Test
    void unknownTagOpenInRoutingMetadataReturnsUsageDirectiveNotDirectExecution() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());
        ToolRoutingMetadataRepository routingRepository = mock(ToolRoutingMetadataRepository.class);
        when(routingRepository.findByToolId("q2_1_metrics_by_dept_version"))
                .thenReturn(Optional.of(new ToolRoutingMetadata("q2_1_metrics_by_dept_version",
                        ToolRoutingToolType.SQL, "desc", List.of(), List.of(), List.of(), 0, true, null)));

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService(),
                routingRepository, null, null);

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of("质量"), List.of("q2_1_metrics_by_dept_version"), List.of(), List.of(), 10);
        assertTrue(result instanceof String);
        String directive = (String) result;
        assertTrue(directive.contains("tool_index 可发现工具的 ID"));
        assertTrue(directive.contains("不能作为 topicTags/metricTags 标签传入"));
    }

    @Test
    void unknownTagDisabledInRoutingMetadataReturnsDirectExecutionDirective() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());
        ToolRoutingMetadataRepository routingRepository = mock(ToolRoutingMetadataRepository.class);
        when(routingRepository.findByToolId("q2_1_metrics_by_dept_version"))
                .thenReturn(Optional.of(new ToolRoutingMetadata("q2_1_metrics_by_dept_version",
                        ToolRoutingToolType.SQL, "desc", List.of(), List.of(), List.of(), 0, false, null)));

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService(),
                routingRepository, null, null);

        Object result = tool.toolIndex(RuntimeContext.empty(),
                List.of("质量"), List.of("q2_1_metrics_by_dept_version"), List.of(), List.of(), 10);
        assertTrue(result instanceof String);
        assertTrue(((String) result).contains("Skill 指定的固定 ID 工具是隐藏的"));
    }

    @Test
    void consecutiveDiscoveryCallsWithoutExecutionTripCircuitBreaker() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());
        RuntimeContext ctx = RuntimeContext.empty();

        // Three-level protocol: topic -> metric -> dimension = 3 calls allowed.
        Object first = tool.toolIndex(ctx, List.of("QI卡口"), List.of(), List.of(), List.of(), 10);
        Object second = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of(), List.of(), 10);
        Object third = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of("不存在维度"), List.of(), 10);
        assertTrue(first instanceof ToolIndexResponse);
        assertTrue(second instanceof ToolIndexResponse);
        assertTrue(third instanceof ToolIndexResponse);

        // 4th consecutive call (no executor in between) -> stop directive.
        Object fourth = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of(), List.of(), 10);
        assertTrue(fourth instanceof String);
        String directive = (String) fourth;
        assertTrue(directive.contains("禁止继续发现查询"));
        assertTrue(directive.contains("当前不支持该指标查询"));
    }

    @Test
    void executorResetViaContextKeyRestoresDiscovery() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(catalog());

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());
        RuntimeContext ctx = RuntimeContext.empty();

        tool.toolIndex(ctx, List.of("QI卡口"), List.of(), List.of(), List.of(), 10);
        tool.toolIndex(ctx, List.of("QI卡口"), List.of(), List.of(), List.of(), 10);
        tool.toolIndex(ctx, List.of("QI卡口"), List.of(), List.of(), List.of(), 10);
        // DiscoveryStreakResetMiddleware does this when an executor tool is invoked.
        ctx.put(ToolIndexTool.DISCOVERY_STREAK_KEY, 0);

        Object afterReset = tool.toolIndex(ctx, List.of("QI卡口"), List.of("质量分"), List.of(), List.of(), 10);
        assertTrue(afterReset instanceof ToolIndexResponse);
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
