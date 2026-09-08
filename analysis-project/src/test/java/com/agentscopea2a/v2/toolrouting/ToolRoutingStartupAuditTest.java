package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingStartupAuditTest {

    private final ToolRoutingScanService scanService = mock(ToolRoutingScanService.class);
    private final ToolRoutingMetadataRepository metadataRepository = mock(ToolRoutingMetadataRepository.class);
    @Test
    void cleanCatalogPassesEvenInStrictMode() {
        when(scanService.scan()).thenReturn(List.of(candidate("sql_ok", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("sql_ok", true)));
        assertDoesNotThrow(() -> new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), true)
                .run(new DefaultApplicationArguments()));
    }

    @Test
    void nonStrictModeRecordsIssuesWithoutBlocking() {
        when(scanService.scan()).thenReturn(List.of(candidate("sql_missing_meta", true, false)));
        when(metadataRepository.findAll()).thenReturn(List.of());

        assertDoesNotThrow(() -> new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), false)
                .run(new DefaultApplicationArguments()));
    }

    @Test
    void strictModeBlocksStartupOnIssues() {
        when(scanService.scan()).thenReturn(List.of(candidate("sql_missing_meta", true, false)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("orphan_script", true)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new ToolRoutingStartupAudit(scanService, metadataRepository,
                        ToolRoutingMetrics.noop(), true)
                        .run(new DefaultApplicationArguments()));
        assertTrue(ex.getMessage().contains("strict-startup"));
    }

    @Test
    void auditClassifiesMissingOrphanTagsAndBindings() {
        when(scanService.scan()).thenReturn(List.of(
                candidate("sql_missing_meta", true, false),
                candidate("dup_id", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(
                metadata("orphan_script", true),
                new ToolRoutingMetadata("no_tags", ToolRoutingToolType.SQL, "desc",
                        List.of(), List.of(), List.of(), 0, true, null),
                metadata("bound_tool", true)));

        List<ToolRoutingStartupAudit.AuditIssue> issues =
                new ToolRoutingStartupAudit(scanService, metadataRepository,
                        ToolRoutingMetrics.noop(), false).audit();

        assertEquals(5, issues.size());
        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("missing") && i.toolId().equals("sql_missing_meta")));
        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("orphan") && i.toolId().equals("orphan_script")));
        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("orphan") && i.toolId().equals("no_tags")));
        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("invalid_tags") && i.toolId().equals("no_tags")));
    }

    @Test
    void disabledMetadataIsNotAudited() {
        when(scanService.scan()).thenReturn(List.of());
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("disabled_orphan", false)));

        assertEquals(0, new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), true).audit().size());
    }

    private static ToolRoutingScanCandidate candidate(String toolId, boolean sourceAvailable, boolean configured) {
        return new ToolRoutingScanCandidate(toolId, ToolRoutingToolType.SQL, toolId, "desc",
                sourceAvailable, configured, configured, List.of());
    }

    private static ToolRoutingMetadata metadata(String toolId, boolean enabled) {
        return new ToolRoutingMetadata(toolId, ToolRoutingToolType.SQL, "desc",
                List.of("QI卡口"), List.of("达标率"), List.of("部门"), 0, enabled, null);
    }

}
