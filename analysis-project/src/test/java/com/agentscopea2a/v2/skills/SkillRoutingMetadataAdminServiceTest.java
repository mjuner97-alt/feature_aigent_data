package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRoutingMetadataAdminServiceTest {

    private final SkillRoutingMetadataRepository repository = mock(SkillRoutingMetadataRepository.class);
    private final SkillRoutingMetadataAdminService service = new SkillRoutingMetadataAdminService(repository);

    @Test
    void saveNormalizesAndDeduplicatesTags() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                " Q2 summary ", List.of(" q2 ", "q2", ""), List.of(" 达标率 "),
                List.of(), List.of("quality"), List.of("gauss"), 10, true));

        assertEquals("Q2 summary", result.shortSummary());
        assertEquals(List.of("q2"), result.aliases());
        assertEquals(List.of("达标率"), result.keywords());
        verify(repository).upsert(result);
    }

    @Test
    void saveSplitsChineseListSeparatorsInsideTagValues() {
        when(repository.skillExists("q2_skill")).thenReturn(true);
        when(repository.upsert(any())).thenReturn(true);

        SkillRoutingMetadata result = service.save("q2_skill", new SkillRoutingMetadataInput(
                "summary", List.of(), List.of("Q2-1、部门、版本、达标率", "打分率，项目总数"),
                List.of(), List.of(), List.of(), 10, true));

        assertEquals(List.of("Q2-1", "部门", "版本", "达标率", "打分率", "项目总数"), result.keywords());
    }

    @Test
    void saveRejectsUnknownSkill() {
        when(repository.skillExists("missing")).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.save("missing", emptyInput(0)));

        assertEquals("SkillNotFound: missing", error.getMessage());
    }

    @Test
    void saveRejectsPriorityOutsideSupportedRange() {
        when(repository.skillExists("q2_skill")).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.save("q2_skill", emptyInput(1001)));

        assertEquals("PriorityOutOfRange: -1000..1000", error.getMessage());
    }

    private static SkillRoutingMetadataInput emptyInput(int priority) {
        return new SkillRoutingMetadataInput("summary", List.of(), List.of(), List.of(), List.of(), List.of(),
                priority, true);
    }
}
