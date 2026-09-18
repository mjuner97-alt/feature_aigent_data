package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.SkillRoutingMetadata;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillDescriptionSimilarityServiceTest {

    private SkillRoutingMetadataRepository skillRoutingRepo;

    @BeforeEach
    void setUp() {
        skillRoutingRepo = mock(SkillRoutingMetadataRepository.class);
        when(skillRoutingRepo.findAll()).thenReturn(List.of());
    }

    private SkillDescriptionSimilarityService service(SkillDescriptionSource source) {
        return service(source, true);
    }

    private SkillDescriptionSimilarityService service(SkillDescriptionSource source, boolean enabled) {
        // EmbeddingClient=null -> degraded, 只跑 L1 文本信号
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, source, null);
        cache.startWarmup();
        return new SkillDescriptionSimilarityService(enabled, source, skillRoutingRepo, cache,
                0.85, 0.85, 0.60);
    }

    private static SkillDescriptionSource.SkillDescriptionRow row(long id, String name,
            String description, String retrievalName, String ownerUserId, String visibility) {
        return new SkillDescriptionSource.SkillDescriptionRow(id, name, description, ownerUserId,
                retrievalName, visibility);
    }

    @Test
    void similarDescriptionByJaccard() {
        SkillDescriptionSource source = () -> List.of(
                row(1, "质量统计", "按部门统计质量分和达标率并输出月报", "page_1", "u_a", "PUBLIC"),
                row(2, "周报生成", "生成项目周报和成员工作量清单", "page_2", "u_b", "PRIVATE"));
        SkillSimilarityCheckResult result = service(source).check(
                "新技能", "按部门统计质量分和达标率并输出周报", null);

        assertTrue(result.degraded());
        assertEquals(1, result.matches().size());
        SkillSimilarityCheckResult.SkillSimilarityMatch match = result.matches().get(0);
        assertEquals(Long.valueOf(1L), match.skillId());
        assertEquals("u_a", match.ownerUserId());
        assertTrue(match.similarity() >= 0.6);
    }

    @Test
    void nameNearMatchAgainstNameAndRoutingKeywords() {
        SkillDescriptionSource source = () -> List.of(
                row(1, "缺陷密度查询", "不相关的描述内容", "page_1", "u_a", "PERSONAL"));
        // 路由 keywords 提供别名命中通道
        when(skillRoutingRepo.findAll()).thenReturn(List.of(new SkillRoutingMetadata(
                "page_1", "", List.of("defect_density"), List.of(), List.of(),
                "u_a", true, null)));

        SkillSimilarityCheckResult result = service(source).check(
                "defect-density", "完全不同的描述", null);
        assertEquals(1, result.matches().size());
        assertTrue(result.matches().get(0).evidence().size() >= 1);

        // retrievalName 也是比对目标
        SkillSimilarityCheckResult byRetrievalName = service(source).check(
                "page-1", "完全不同的描述", null);
        assertEquals(1, byRetrievalName.matches().size());
    }

    @Test
    void excludeSkillIdSkipsSelf() {
        SkillDescriptionSource source = () -> List.of(
                row(7, "质量统计", "按部门统计质量分和达标率并输出月报", "page_7", "u_a", "PUBLIC"));
        SkillSimilarityCheckResult result = service(source).check(
                "新名字", "按部门统计质量分和达标率并输出月报", 7L);
        assertTrue(result.matches().isEmpty(), "excludeSkillId must skip self");
    }

    @Test
    void unrelatedSkillYieldsNoMatch() {
        SkillDescriptionSource source = () -> List.of(
                row(1, "周报生成", "生成项目周报和成员工作量清单", "page_1", "u_b", "PRIVATE"));
        SkillSimilarityCheckResult result = service(source).check(
                "环境巡检", "巡检服务器磁盘与内存水位并告警", null);
        assertTrue(result.matches().isEmpty());
    }

    @Test
    void emptySourceReturnsNoMatches() {
        SkillDescriptionSource source = List::of;
        SkillSimilarityCheckResult result = service(source).check("x", "y", null);
        assertTrue(result.matches().isEmpty());
    }

    @Test
    void disabledReturnsEmptyResult() {
        SkillDescriptionSource source = () -> List.of(
                row(1, "质量统计", "按部门统计质量分和达标率并输出月报", "page_1", "u_a", "PUBLIC"));
        SkillSimilarityCheckResult result = service(source, false).check(
                "质量统计", "按部门统计质量分和达标率并输出月报", null);
        assertTrue(result.matches().isEmpty());
        assertTrue(!result.degraded());
    }
}
