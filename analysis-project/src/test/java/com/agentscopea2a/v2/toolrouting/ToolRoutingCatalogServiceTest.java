package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingCatalogServiceTest {

    @Test
    void onlyPublishesTagsForAvailableTools() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        ToolRoutingMetadata available = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality", List.of("QI卡口"), List.of("质量分"), List.of("部门"), 0, true, null);
        ToolRoutingMetadata unavailable = new ToolRoutingMetadata("missing_script", ToolRoutingToolType.SCRIPT,
                "missing", List.of("失效主题"), List.of("失效指标"), List.of("版本"), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(available, unavailable));
        when(resolver.isAvailable(available)).thenReturn(true);
        when(resolver.isAvailable(unavailable)).thenReturn(false);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, resolver).snapshot();

        assertEquals(List.of("quality_sql"), catalog.tools().stream().map(ToolRoutingMetadata::toolId).toList());
        assertEquals(List.of("QI卡口"), catalog.topicTags().stream().sorted().toList());
        assertEquals(List.of("质量分"), catalog.metricTags().stream().sorted().toList());
        assertEquals(List.of("部门"), catalog.dimensionTags().stream().sorted().toList());
    }

    @Test
    void publishesTagsMissingFromTagDictionary() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        // 后端直插 tool_route_metadata 的标签, 词袋 tool_route_tag_dictionary 里没有对应行
        ToolRoutingMetadata imported = new ToolRoutingMetadata("imported_api", ToolRoutingToolType.API,
                "imported", List.of("新主题"), List.of("新指标"), List.of("新维度"), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(imported));
        when(resolver.isAvailable(imported)).thenReturn(true);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, resolver).snapshot();

        assertEquals(List.of("新主题"), catalog.topicTags().stream().sorted().toList());
        assertEquals(List.of("新指标"), catalog.metricTags().stream().sorted().toList());
        assertEquals(List.of("新维度"), catalog.dimensionTags().stream().sorted().toList());
    }

    @Test
    void dedupesTagsCaseInsensitiveKeepingOriginalCasing() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        ToolRoutingMetadata tool = new ToolRoutingMetadata("dup_sql", ToolRoutingToolType.SQL,
                "dup", List.of("QI卡口", "qi卡口 "), List.of("质量分", " 质量分"), List.of(), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(tool));
        when(resolver.isAvailable(tool)).thenReturn(true);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, resolver).snapshot();

        assertEquals(List.of("QI卡口"), catalog.topicTags().stream().toList());
        assertEquals(List.of("质量分"), catalog.metricTags().stream().toList());
    }

    @Test
    void publishesLegacyMetadataWithoutSkillContext() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        ToolRoutingMetadata legacyScoped = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(legacyScoped));
        when(resolver.isAvailable(legacyScoped)).thenReturn(true);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, resolver).snapshot();

        assertEquals(List.of("quality_sql"), catalog.tools().stream().map(ToolRoutingMetadata::toolId).toList());
    }
}
