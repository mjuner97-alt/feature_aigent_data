package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

class SkillRoutingMetadataAdminServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultOverlapReport() {
        when(skillSkillOverlapService.report()).thenReturn(
                new com.agentscopea2a.v2.governance.SkillSkillOverlapService.OverlapReport(
                        false, List.of()));
    }

    private final SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
    private final com.agentscopea2a.v2.governance.SkillSkillOverlapService skillSkillOverlapService =
            mock(com.agentscopea2a.v2.governance.SkillSkillOverlapService.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository> dimensionKeyRepoProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
    private final SkillRoutingMetadataAdminService service =
            new SkillRoutingMetadataAdminService(repository, null, skillSkillOverlapService,
                    new com.agentscopea2a.v2.auth.service.AdminRoleService("", false), dimensionKeyRepoProvider);
    private final SkillRoutingMetadataAdminService prodService =
            new SkillRoutingMetadataAdminService(repository, null, skillSkillOverlapService,
                    new com.agentscopea2a.v2.auth.service.AdminRoleService("admin:secret1", true), dimensionKeyRepoProvider);

    @Test
    void saveNormalizesAndDeduplicatesTags() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                " Q2 summary ", List.of(" 达标率 "), List.of("质量管理"),
                List.of("QI卡口"), true), "admin");

        assertEquals("Q2 summary", result.shortSummary());
        assertEquals(List.of("达标率"), result.keywords());
        assertEquals(List.of("质量管理"), result.domainTags());
        assertEquals(List.of("QI卡口"), result.topicTags());
        assertEquals("admin", result.creator());
        verify(repository).upsert(result);
    }

    @Test
    void saveSplitsChineseListSeparatorsInsideTagValues() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                "summary", List.of("Q2-1、部门、版本、达标率", "打分率，项目总数"),
                List.of(), List.of(), true), "admin");

        assertEquals(List.of("Q2-1", "部门", "版本", "达标率", "打分率", "项目总数"), result.keywords());
    }

    @Test
    void saveRejectsUnknownSkill() {
        when(repository.skillExists("missing")).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.save("missing", emptyInput()));

        assertEquals("SkillNotFound: missing", error.getMessage());
    }

    @Test
    void listPassesCurrentUserAndMineFilterToRepository() {
        service.list("alice", null, true, "alice", 200, 0);

        verify(repository).findAllWithSkillManage(eq("alice"), eq(null), eq(true), eq("alice"), eq(200), eq(0));
    }

    @Test
    void productionModeBlocksOwnerButAllowsAdmin() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");

        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> prodService.save("q2_skill", emptyInput(), "alice"));
        assertEquals("ResourceAccessDenied", denied.getMessage());

        when(repository.upsert(any())).thenReturn(true);
        prodService.save("q2_skill", emptyInput(), "admin");
        verify(repository).upsert(any());
    }

    private static SkillRoutingMetadataInput emptyInput() {
        return new SkillRoutingMetadataInput("summary", List.of(), List.of(), List.of(), true);
    }

    @Test
    void saveAcceptsDynamicDimensionKeyFromDictionaryAndRejectsUnknown() {
        com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository keyRepo =
                mock(com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository.class);
        when(keyRepo.findAllEnabled()).thenReturn(List.of(
                new com.agentscopea2a.v2.dimension.DimensionKeyConfig(9L, "qGate", "卡口",
                        false, List.of(), true, null)));
        when(dimensionKeyRepoProvider.getIfAvailable()).thenReturn(keyRepo);
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                "summary", List.of(), List.of(), List.of(), true,
                List.of(), List.of(), List.of(), "script_a", java.util.Map.of("gate", "qGate")), "admin");
        org.junit.jupiter.api.Assertions.assertEquals("{\"gate\":\"qGate\"}", result.paramMapping());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.save("q2_skill", new SkillRoutingMetadataInput(
                        "summary", List.of(), List.of(), List.of(), true,
                        List.of(), List.of(), List.of(), "script_a", java.util.Map.of("gate", "notAKey")), "admin"));
        assertTrue(error.getMessage().startsWith("ParamMappingInvalidDimension"));
        assertTrue(error.getMessage().contains("qGate"));
    }

    @Test
    void paramMappingAcceptsBuiltinSuffixKeysEvenWithoutDictionary() {
        // versionPlan/vestQuarter 是内置键, .start/.end 后缀运行时无条件可用 (activeKeys 同源),
        // 字典读不到也必须放行
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                "summary", List.of(), List.of(), List.of(), true,
                List.of(), List.of(), List.of(), "script_a",
                java.util.Map.of("startDate", "versionPlan.start", "endDate", "versionPlan.end",
                        "qStart", "vestQuarter.start", "qEnd", "vestQuarter.end",
                        "trStart", "versionPlan")), "admin");
        org.junit.jupiter.api.Assertions.assertTrue(result.paramMapping().contains("versionPlan.start"));
    }

    private static com.agentscopea2a.v2.governance.SkillSkillOverlapView highView(
            String nameA, boolean activeA, String nameB, boolean activeB) {
        return new com.agentscopea2a.v2.governance.SkillSkillOverlapView(
                nameA, "alice", nameB, "bob", "HIGH",
                List.of("质检"), List.of("质检"), List.of("缺陷"), List.of("缺陷"),
                List.of("缺陷"), 1.0, true, List.of(), "suggestion", activeA, activeB);
    }

    @Test
    void enablingSkillWithHighOverlapAgainstActivePeerIsBlocked() {
        when(skillSkillOverlapService.report()).thenReturn(
                new com.agentscopea2a.v2.governance.SkillSkillOverlapService.OverlapReport(
                        false, List.of(highView("skill_alpha", true, "q2_skill", false))));
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.save("q2_skill", emptyInput(), "admin"));

        assertTrue(error.getMessage().startsWith("SkillOverlapBlocked: 与 Skill skill_alpha"));
        assertTrue(error.getMessage().contains("至多启用一个"));
    }

    @Test
    void enablingSkillWithHighOverlapAgainstInactivePeerIsAllowed() {
        when(skillSkillOverlapService.report()).thenReturn(
                new com.agentscopea2a.v2.governance.SkillSkillOverlapService.OverlapReport(
                        false, List.of(highView("skill_alpha", false, "q2_skill", false))));
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        service.save("q2_skill", emptyInput(), "admin");

        verify(repository).upsert(any());
    }

    @Test
    void disablingSkillIsNeverBlockedByOverlap() {
        when(skillSkillOverlapService.report()).thenReturn(
                new com.agentscopea2a.v2.governance.SkillSkillOverlapService.OverlapReport(
                        false, List.of(highView("skill_alpha", true, "q2_skill", true))));
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("admin");
        when(repository.upsert(any())).thenReturn(true);

        service.save("q2_skill", new SkillRoutingMetadataInput(
                "summary", List.of(), List.of(), List.of(), false), "admin");

        verify(repository).upsert(any());
    }

    // ==================== saveDirectExec: 直接执行配置独立权限 ====================

    private void stubExistingMetadata(String creator) {
        SkillRoutingMetadata current = new SkillRoutingMetadata("q2_skill",
                "existing summary", List.of("达标率"), List.of("质量管理"), List.of(),
                creator, true, null,
                List.of("问题"), List.of("未闭环"), List.of("严重"),
                null, null);
        when(repository.findBySkillName("q2_skill")).thenReturn(java.util.Optional.of(current));
    }

    @Test
    void saveDirectExecAllowsOwnerInProductionMode() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");
        when(repository.upsert(any())).thenReturn(true);
        stubExistingMetadata("alice");

        SkillRoutingMetadata result = prodService.saveDirectExec("q2_skill",
                new SkillRoutingMetadataAdminService.DirectExecInput("script_a",
                        java.util.Map.of("dept", "deptName")), "alice");

        assertEquals("script_a", result.scriptId());
        verify(repository).upsert(any());
    }

    @Test
    void saveDirectExecRejectsNonOwnerEvenInTestMode() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");

        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> service.saveDirectExec("q2_skill",
                        new SkillRoutingMetadataAdminService.DirectExecInput("script_a", java.util.Map.of()), "bob"));
        assertEquals("ResourceAccessDenied", denied.getMessage());
    }

    @Test
    void saveDirectExecAllowsAdminForAnySkillInProductionMode() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");
        when(repository.upsert(any())).thenReturn(true);
        stubExistingMetadata("alice");

        prodService.saveDirectExec("q2_skill",
                new SkillRoutingMetadataAdminService.DirectExecInput("script_a", java.util.Map.of()), "admin");

        verify(repository).upsert(any());
    }

    @Test
    void saveDirectExecOnlyOverwritesDirectExecFields() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");
        when(repository.upsert(any())).thenReturn(true);
        stubExistingMetadata("alice");

        service.saveDirectExec("q2_skill",
                new SkillRoutingMetadataAdminService.DirectExecInput("script_b",
                        java.util.Map.of("dept", "deptName", "version", "versionPlan")), "alice");

        verify(repository).upsert(argThat((SkillRoutingMetadata m) ->
                "q2_skill".equals(m.skillName())
                        && "existing summary".equals(m.shortSummary())
                        && List.of("达标率").equals(m.keywords())
                        && List.of("质量管理").equals(m.domainTags())
                        && "alice".equals(m.creator())
                        && m.active()
                        && List.of("问题").equals(m.level1Keywords())
                        && "script_b".equals(m.scriptId())
                        && m.paramMapping() != null
                        && m.paramMapping().contains("versionPlan")));
    }

    @Test
    void saveDirectExecValidatesDimensionKeys() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");
        stubExistingMetadata("alice");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveDirectExec("q2_skill",
                        new SkillRoutingMetadataAdminService.DirectExecInput("script_a",
                                java.util.Map.of("dept", "notAKey")), "alice"));
        assertTrue(error.getMessage().startsWith("ParamMappingInvalidDimension"));
    }

    @Test
    void saveDirectExecSkipsHighOverlapBlock() {
        when(skillSkillOverlapService.report()).thenReturn(
                new com.agentscopea2a.v2.governance.SkillSkillOverlapService.OverlapReport(
                        false, List.of(highView("skill_alpha", true, "q2_skill", true))));
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.creatorForSkill("q2_skill")).thenReturn("alice");
        when(repository.upsert(any())).thenReturn(true);
        stubExistingMetadata("alice");

        service.saveDirectExec("q2_skill",
                new SkillRoutingMetadataAdminService.DirectExecInput("script_a", java.util.Map.of()), "alice");

        verify(repository).upsert(any());
    }
}
