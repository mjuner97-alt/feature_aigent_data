package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

class SkillRoutingMetadataAdminServiceTest {

    private final SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
    private final SkillRoutingMetadataAdminService service =
            new SkillRoutingMetadataAdminService(repository, null, new com.agentscopea2a.v2.auth.service.AdminRoleService("", false));
    private final SkillRoutingMetadataAdminService prodService =
            new SkillRoutingMetadataAdminService(repository, null, new com.agentscopea2a.v2.auth.service.AdminRoleService("admin:secret1", true));

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
}
