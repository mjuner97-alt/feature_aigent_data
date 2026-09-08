package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ToolRoutingMetadataAdminServiceTest {

    @Test
    void normalizesMetadataBeforePersisting() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(repository);

        ToolRoutingMetadata saved = service.save("q2_metrics", new ToolRoutingMetadataInput(
                ToolRoutingToolType.SCRIPT, "  按部门统计质量分  ", List.of(" QI卡口 "), List.of(" 质量分 ", "质量分"),
                List.of(" 部门 "), 10, true));

        assertEquals("q2_metrics", saved.toolId());
        assertEquals("按部门统计质量分", saved.description());
        assertEquals(List.of("QI卡口"), saved.topicTags());
        assertEquals(List.of("质量分"), saved.metricTags());
        assertEquals(List.of("部门"), saved.dimensionTags());
        verify(repository).upsert(saved);
    }

    @Test
    void rejectsOutOfRangePriority() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.save("q2_metrics",
                new ToolRoutingMetadataInput(ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 1001, true)));
    }

}
