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

    @Test
    void toolOverlapIssuesAppearInAudit() {
        when(scanService.scan()).thenReturn(List.of(candidate("t_a", true, true), candidate("t_b", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("t_a", true), metadata("t_b", true)));
        ToolRoutingStartupAudit audit = new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), false, overlapServiceWithHighPair("t_a", "t_b"), false);

        List<ToolRoutingStartupAudit.AuditIssue> issues = audit.audit();

        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("tool_overlap")
                && i.toolId().equals("t_a") && i.detail().contains("与工具 t_b 有重复，请处理")));
        assertTrue(issues.stream().anyMatch(i -> i.kind().equals("tool_overlap")
                && i.toolId().equals("t_b") && i.detail().contains("与工具 t_a 有重复，请处理")));
    }

    @Test
    void blockToolOverlapBlocksStartup() {
        when(scanService.scan()).thenReturn(List.of(candidate("t_a", true, true), candidate("t_b", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("t_a", true), metadata("t_b", true)));
        ToolRoutingStartupAudit audit = new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), false, overlapServiceWithHighPair("t_a", "t_b"), true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> audit.run(new DefaultApplicationArguments()));
        assertTrue(ex.getMessage().contains("与工具 t_b 有重复，请处理"));
    }

    @Test
    void blockToolOverlapDisabledOnlyWarns() {
        when(scanService.scan()).thenReturn(List.of(candidate("t_a", true, true), candidate("t_b", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("t_a", true), metadata("t_b", true)));
        ToolRoutingStartupAudit audit = new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), false, overlapServiceWithHighPair("t_a", "t_b"), false);

        assertDoesNotThrow(() -> audit.run(new DefaultApplicationArguments()));
    }

    @Test
    void mediumOverlapDoesNotBlock() {
        when(scanService.scan()).thenReturn(List.of(candidate("t_a", true, true), candidate("t_b", true, true)));
        when(metadataRepository.findAll()).thenReturn(List.of(metadata("t_a", true), metadata("t_b", true)));
        com.agentscopea2a.v2.governance.ToolToolOverlapService mediumOnly =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(mediumOnly.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                        "t_a", "SQL", "t_b", "SQL", "MEDIUM",
                        List.of("QI卡口"), 0.82, false, false,
                        List.of("描述"), List.of(), List.of(), List.of(), List.of(),
                        "描述相近，请人工确认"))));
        ToolRoutingStartupAudit audit = new ToolRoutingStartupAudit(scanService, metadataRepository,
                ToolRoutingMetrics.noop(), false, mediumOnly, true);

        assertDoesNotThrow(() -> audit.run(new DefaultApplicationArguments()));
        assertTrue(audit.audit().stream().noneMatch(i -> i.kind().equals("tool_overlap")));
    }

    private static com.agentscopea2a.v2.governance.ToolToolOverlapService overlapServiceWithHighPair(
            String toolIdA, String toolIdB) {
        com.agentscopea2a.v2.governance.ToolToolOverlapService service =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(service.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                        toolIdA, "SQL", toolIdB, "SQL", "HIGH",
                        List.of("QI卡口"), 0.93, false, false,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        "重复，请处理"))));
        return service;
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
