package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.v2.common.PageResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingMetadataControllerTest {

    private static ToolRoutingScanCandidate candidate(String toolId, ToolRoutingToolType type, String description) {
        return new ToolRoutingScanCandidate(toolId, type, toolId, description, "", "", true,
                false, false, List.of());
    }

    @Test
    void scanFiltersKeywordAcrossAllPagesBeforePagination() {
        // 搜索框要求搜到所有分页的内容: keyword 必须在分页前过滤, total 是过滤后总数
        ToolRoutingScanService scanService = mock(ToolRoutingScanService.class);
        when(scanService.scan()).thenReturn(List.of(
                candidate("q2_1_dup", ToolRoutingToolType.SCRIPT, "脚本计算聚合数"),
                candidate("q2_1_dup", ToolRoutingToolType.SQL, "SQL 查询聚合数"),
                candidate("density_check", ToolRoutingToolType.API, "缺陷密度校验")));
        ToolRoutingMetadataController controller = new ToolRoutingMetadataController(
                mock(ToolRoutingMetadataAdminService.class), scanService);

        PageResponse<ToolRoutingScanCandidate> result = controller.scan(1, 1, "聚合", null);
        assertEquals(2, result.total(), "keyword filters the full list, not the current page");
        assertEquals(1, result.items().size());
        assertEquals(1, controller.scan(2, 1, "聚合", null).items().size(), "second page holds the remaining hit");
    }

    @Test
    void scanFiltersByToolType() {
        ToolRoutingScanService scanService = mock(ToolRoutingScanService.class);
        when(scanService.scan()).thenReturn(List.of(
                candidate("q2_1_dup", ToolRoutingToolType.SCRIPT, "脚本"),
                candidate("q2_1_dup", ToolRoutingToolType.SQL, "SQL 查询")));
        ToolRoutingMetadataController controller = new ToolRoutingMetadataController(
                mock(ToolRoutingMetadataAdminService.class), scanService);

        PageResponse<ToolRoutingScanCandidate> result = controller.scan(1, 20, null, "SQL");
        assertEquals(1, result.total());
        assertEquals(ToolRoutingToolType.SQL, result.items().get(0).toolType());
    }
}
