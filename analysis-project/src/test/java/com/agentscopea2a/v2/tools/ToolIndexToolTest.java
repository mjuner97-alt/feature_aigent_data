package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.toolrouting.ToolIndexService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalog;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolIndexToolTest {

    @Test
    void delegatesStructuredFiltersWithoutAcceptingTheRawQuestion() {
        ToolRoutingCatalogService catalogService = mock(ToolRoutingCatalogService.class);
        when(catalogService.snapshot()).thenReturn(new ToolRoutingCatalog(List.of(), Set.of("QI卡口"), Set.of("质量分"), Set.of()));

        ToolIndexTool tool = new ToolIndexTool(catalogService, new ToolIndexService());

        assertEquals(List.of("质量分"), tool.toolIndex(List.of("QI卡口"), List.of("质量分"), List.of(), List.of(), 10)
                .filters().metricTags());
    }
}
