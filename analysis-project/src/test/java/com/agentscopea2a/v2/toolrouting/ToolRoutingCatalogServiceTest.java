package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingCatalogServiceTest {

    @Test
    void onlyPublishesEnabledTagsForAvailableTools() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingTagDictionary dictionary = mock(ToolRoutingTagDictionary.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        ToolRoutingMetadata available = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality", List.of("QI卡口"), List.of("质量分"), List.of("部门"), 0, true, null);
        ToolRoutingMetadata unavailable = new ToolRoutingMetadata("missing_script", ToolRoutingToolType.SCRIPT,
                "missing", List.of("失效主题"), List.of("失效指标"), List.of("版本"), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(available, unavailable));
        when(dictionary.findEnabled(ToolRoutingTagType.TOPIC)).thenReturn(List.of(
                new ToolRoutingTag(ToolRoutingTagType.TOPIC, "QI卡口", "", true)));
        when(dictionary.findEnabled(ToolRoutingTagType.METRIC)).thenReturn(List.of(
                new ToolRoutingTag(ToolRoutingTagType.METRIC, "质量分", "", true)));
        when(dictionary.findEnabled(ToolRoutingTagType.DIMENSION)).thenReturn(List.of(
                new ToolRoutingTag(ToolRoutingTagType.DIMENSION, "部门", "", true),
                new ToolRoutingTag(ToolRoutingTagType.DIMENSION, "版本", "", true)));
        when(resolver.isAvailable(available)).thenReturn(true);
        when(resolver.isAvailable(unavailable)).thenReturn(false);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, dictionary, resolver).snapshot();

        assertEquals(List.of("quality_sql"), catalog.tools().stream().map(ToolRoutingMetadata::toolId).toList());
        assertEquals(List.of("QI卡口"), catalog.topicTags().stream().sorted().toList());
        assertEquals(List.of("质量分"), catalog.metricTags().stream().sorted().toList());
        assertEquals(List.of("部门"), catalog.dimensionTags().stream().sorted().toList());
    }

    @Test
    void publishesLegacyMetadataWithoutSkillContext() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingTagDictionary dictionary = mock(ToolRoutingTagDictionary.class);
        ToolRoutingAvailabilityResolver resolver = mock(ToolRoutingAvailabilityResolver.class);
        ToolRoutingMetadata legacyScoped = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true, null);
        when(repository.findEnabled()).thenReturn(List.of(legacyScoped));
        when(dictionary.findEnabled(ToolRoutingTagType.TOPIC)).thenReturn(List.of(
                new ToolRoutingTag(ToolRoutingTagType.TOPIC, "QI卡口", "", true)));
        when(dictionary.findEnabled(ToolRoutingTagType.METRIC)).thenReturn(List.of(
                new ToolRoutingTag(ToolRoutingTagType.METRIC, "质量分", "", true)));
        when(dictionary.findEnabled(ToolRoutingTagType.DIMENSION)).thenReturn(List.of());
        when(resolver.isAvailable(legacyScoped)).thenReturn(true);

        ToolRoutingCatalog catalog = new ToolRoutingCatalogService(repository, dictionary, resolver).snapshot();

        assertEquals(List.of("quality_sql"), catalog.tools().stream().map(ToolRoutingMetadata::toolId).toList());
    }
}
