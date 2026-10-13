package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.governance.SkillSkillOverlapService;
import com.agentscopea2a.v2.governance.SkillSkillOverlapView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRoutingStartupAuditTest {

    private SkillSkillOverlapService overlapService;
    private SkillRoutingMetadataRepository repository;

    @BeforeEach
    void setUp() {
        overlapService = mock(SkillSkillOverlapService.class);
        repository = mock(SkillRoutingMetadataRepository.class);
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false, List.of()));
    }

    private static SkillSkillOverlapView highPair(String nameA, boolean activeA,
                                                  String nameB, boolean activeB) {
        return new SkillSkillOverlapView(nameA, "alice", nameB, "bob", "HIGH",
                List.of("质检"), List.of("质检"), List.of("缺陷"), List.of("缺陷"),
                List.of("缺陷"), 1.0, true, List.of(), "suggestion", activeA, activeB);
    }

    private static SkillRoutingMetadata metadata(String skillName) {
        return new SkillRoutingMetadata(skillName, "summary", List.of("缺陷"), List.of("质检"),
                List.of(), "alice", true, LocalDateTime.of(2026, 10, 10, 0, 0));
    }

    @Test
    void blockDisabledIsZeroCostNoOp() {
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, false, 4);

        audit.run(new DefaultApplicationArguments(new String[0]));

        verify(overlapService, never()).report();
        verify(overlapService, never()).ensureSkillVectorsWarmed();
    }

    @Test
    void bothActiveHighPairAutoDeactivatesLargerNameInsteadOfBlocking() {
        // 自愈优先: 双方 active 的 HIGH 对自动停用 skillName 字典序较大一侧, 不阻断启动
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false,
                        List.of(highPair("skill_a", true, "skill_b", true))));
        when(repository.findBySkillName("skill_b")).thenReturn(java.util.Optional.of(metadata("skill_b")));
        when(repository.upsert(any())).thenReturn(true);
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        assertDoesNotThrow(() -> audit.run(new DefaultApplicationArguments(new String[0])));

        verify(repository).upsert(org.mockito.ArgumentMatchers.argThat(
                m -> m.skillName().equals("skill_b") && !m.active()));
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.argThat(
                m -> m.skillName().equals("skill_a")));
        verify(overlapService).invalidate();
    }

    @Test
    void failedSelfHealingFallsBackToBlockingStartup() {
        // 自愈 upsert 失败 -> 阻断兜底, 异常消息含"与 Skill ... 有重复，请处理"
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false,
                        List.of(highPair("skill_a", true, "skill_b", true))));
        when(repository.findBySkillName("skill_b")).thenReturn(java.util.Optional.of(metadata("skill_b")));
        when(repository.upsert(any())).thenReturn(false);
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> audit.run(new DefaultApplicationArguments(new String[0])));

        assertTrue(error.getMessage().contains("Skill skill_a 与 Skill skill_b 有重复，请处理"));
    }

    @Test
    void affectedSkillsOverThresholdDegradesToWarningWithoutMetadataChange() {
        // 重复 Skill 数超过 max-skills -> 批量数据问题, 降级告警不变更元数据
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false, List.of(
                        highPair("skill_a", true, "skill_b", true),
                        highPair("skill_c", true, "skill_d", true),
                        highPair("skill_e", true, "skill_f", true))));
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        assertDoesNotThrow(() -> audit.run(new DefaultApplicationArguments(new String[0])));

        verify(repository, never()).upsert(any());
    }

    @Test
    void singleSideActiveHighPairIsIgnored() {
        // 单侧未 active 不在路由候选, 无选型风险, 走页面预警
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false,
                        List.of(highPair("skill_a", true, "skill_b", false))));
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        assertDoesNotThrow(() -> audit.run(new DefaultApplicationArguments(new String[0])));

        verify(repository, never()).findBySkillName(anyString());
        verify(repository, never()).upsert(any());
    }

    @Test
    void missingMetadataRowCountsAsFailedSelfHealing() {
        // 元数据行缺失 -> 该对无法自愈, 走阻断兜底
        when(overlapService.report()).thenReturn(
                new SkillSkillOverlapService.OverlapReport(false,
                        List.of(highPair("skill_a", true, "skill_b", true))));
        when(repository.findBySkillName("skill_b")).thenReturn(java.util.Optional.empty());
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        assertThrows(IllegalStateException.class,
                () -> audit.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void warmupRunsBeforeReportWhenBlockEnabled() {
        SkillRoutingStartupAudit audit = new SkillRoutingStartupAudit(
                overlapService, repository, true, 4);

        audit.run(new DefaultApplicationArguments(new String[0]));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(overlapService);
        inOrder.verify(overlapService).ensureSkillVectorsWarmed();
        inOrder.verify(overlapService).report();
    }
}
