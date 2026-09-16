package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillRoutingMetadata;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import com.agentscopea2a.v2.toolrouting.ToolRoutingToolType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillToolOverlapServiceTest {

    private SkillRoutingMetadataRepository skillRoutingRepo;
    private SkillIndexRepository skillIndexRepo;
    private ToolRoutingMetadataRepository toolRoutingRepo;
    private SkillDescriptionSource skillDescriptionSource;

    @BeforeEach
    void setUp() {
        skillRoutingRepo = mock(SkillRoutingMetadataRepository.class);
        skillIndexRepo = mock(SkillIndexRepository.class);
        toolRoutingRepo = mock(ToolRoutingMetadataRepository.class);
        skillDescriptionSource = mock(SkillDescriptionSource.class);
        when(skillRoutingRepo.findActive()).thenReturn(List.of());
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of());
        when(skillDescriptionSource.allActiveSkills()).thenReturn(List.of());
    }

    private SkillToolOverlapService degradedService() {
        // EmbeddingClient=null -> semanticAvailable=false, warm=true -> degraded
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, skillDescriptionSource, null);
        cache.startWarmup();
        return new SkillToolOverlapService(skillRoutingRepo, skillIndexRepo, toolRoutingRepo,
                cache, skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
    }

    private SkillRoutingMetadata skill(String name, String summary, List<String> keywords,
                                       List<String> topicTags, String description, String status) {
        SkillRoutingMetadata metadata = new SkillRoutingMetadata(
                name, summary, keywords, List.of(), topicTags, "u_skill", true, null);
        when(skillRoutingRepo.findActive()).thenReturn(List.of(metadata));
        when(skillIndexRepo.findByName(name)).thenReturn(java.util.Optional.of(new SkillEntry(
                name, null, description, 1, 0, null, status, "user_generated", "u_skill", null)));
        when(skillRoutingRepo.creatorForSkill(name)).thenReturn("u_skill");
        return metadata;
    }

    private void tool(String toolId, String description, List<String> topicTags) {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(new ToolRoutingMetadata(
                toolId, ToolRoutingToolType.SCRIPT, description, topicTags, List.of(), List.of(),
                0, true, null)));
    }

    @Test
    void keywordAliasHitIsHigh() {
        skill("page_1", "查询质量指标并生成报告", List.of("defect_density_query"),
                List.of("缺陷密度"), "查询质量指标并生成报告", "active");
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));

        SkillToolOverlapService service = degradedService();
        List<RoutingOverlapView> items = service.report().items();
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).aliasHit());
        assertTrue(items.get(0).toolIdLiteralInDescription()
                || items.get(0).aliasHit());
    }

    @Test
    void toolIdLiteralInDeclarationIsHigh() {
        skill("page_1", "使用 defect_density_query 工具完成统计", List.of(),
                List.of("缺陷密度"), "使用 defect_density_query 工具完成统计", "active");
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));

        List<RoutingOverlapView> items = degradedService().report().items();
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).toolIdLiteralInDescription());
    }

    @Test
    void topicTagOnlyOverlapIsLowAndSingleTagExcluded() {
        // 共享 2 个业务主题 -> LOW 巡检; 只共享 1 个且无其他信号 -> 不出现
        skill("page_1", "按部门统计质量分和达标率", List.of(),
                List.of("质量分", "达标率"), "按部门统计质量分和达标率", "active");
        tool("t1", "按部门计算质量分和达标率", List.of("质量分", "达标率"));
        assertEquals(1, degradedService().report().items().size());
        assertEquals("LOW", degradedService().report().items().get(0).level());

        // 重置: 只共享 1 个标签 -> 排除
        when(skillRoutingRepo.findActive()).thenReturn(List.of(new SkillRoutingMetadata(
                "page_1", "按部门统计质量分", List.of(), List.of(), List.of("质量分"),
                "u_skill", true, null)));
        assertTrue(degradedService().report().items().isEmpty());
    }

    @Test
    void blacklistedAndOrphanSkillsAreExcluded() {
        skill("page_1", "使用 defect_density_query 工具", List.of(),
                List.of("缺陷密度"), "使用 defect_density_query 工具", "blacklist");
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));
        assertTrue(degradedService().report().items().isEmpty(),
                "skill_index.status=blacklist must be excluded");

        // 孤儿: skill_index 无行
        when(skillRoutingRepo.findActive()).thenReturn(List.of(new SkillRoutingMetadata(
                "page_ghost", "x", List.of("defect_density_query"), List.of(), List.of("缺陷密度"),
                "u", true, null)));
        when(skillIndexRepo.findByName("page_ghost")).thenReturn(java.util.Optional.empty());
        assertTrue(degradedService().report().items().isEmpty(), "orphan routing row must be excluded");
    }

    @Test
    void pageSkillAliveViaSkillManageWhenIndexRowMissing() {
        // skill_index 行缺失 (桥接失败/历史数据), skill_manage 仍 ACTIVE+未删 -> 不算孤儿, 参与检测
        when(skillRoutingRepo.findActive()).thenReturn(List.of(new SkillRoutingMetadata(
                "missing_in_index", "使用 defect_density_query 工具", List.of(), List.of(), List.of("缺陷密度"),
                null, true, null)));
        when(skillIndexRepo.findByName("missing_in_index")).thenReturn(java.util.Optional.empty());
        when(skillDescriptionSource.allActiveSkills()).thenReturn(List.of(
                new SkillDescriptionSource.SkillDescriptionRow(
                        1L, "指标Skill", "使用 defect_density_query 工具", "u_owner",
                        "missing_in_index", "PERSONAL")));
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));

        List<RoutingOverlapView> items = degradedService().report().items();
        assertEquals(1, items.size());
        assertEquals("u_owner", items.get(0).skillOwnerUserId(), "owner 优先取 skill_manage 权威值");
    }

    @Test
    void summaryCountsHighByEntity() {
        skill("page_1", "使用 defect_density_query 工具", List.of(),
                List.of("缺陷密度"), "使用 defect_density_query 工具", "active");
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));

        SkillToolOverlapService.OverlapSummary summary = degradedService().summary();
        assertTrue(summary.degraded());
        assertEquals(1, summary.high());
        assertEquals(Map.of("page_1", 1L), summary.highBySkill());
        assertEquals(Map.of("defect_density_query", 1L), summary.highByTool());
    }

    @Test
    void invalidateForcesRecompute() {
        skill("page_1", "使用 defect_density_query 工具", List.of(),
                List.of("缺陷密度"), "使用 defect_density_query 工具", "active");
        tool("defect_density_query", "查询缺陷密度", List.of("缺陷密度"));

        SkillToolOverlapService service = degradedService();
        assertEquals(1, service.report().items().size());

        // 保存失效后, 新的工具集生效
        toolRoutingRepo = mock(ToolRoutingMetadataRepository.class);
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of());
        // 直接重建 service 模拟失效 -> 重新计算路径
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, skillDescriptionSource, null);
        cache.startWarmup();
        SkillToolOverlapService fresh = new SkillToolOverlapService(skillRoutingRepo, skillIndexRepo,
                toolRoutingRepo, cache, skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
        assertTrue(fresh.report().items().isEmpty());
        assertFalse(service.report().items().isEmpty(), "old service still returns cached result");
    }

    @Test
    void semanticCosineHighIsHigh() throws Exception {
        // embedding stub: 两段描述 -> 夹角很小的向量 (cosine ≈ 0.995)
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                if (text != null && text.contains("缺陷")) return new float[] {1f, 0.1f};
                return new float[] {1f, 0.11f};
            }

            @Override
            public int dimension() {
                return 2;
            }
        };
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(client, skillDescriptionSource, null);
        cache.startWarmup();
        long deadline = System.currentTimeMillis() + 2000;
        while (!cache.warm() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        skill("page_1", "按部门查询缺陷密度数据", List.of(),
                List.of(), "按部门查询缺陷密度数据", "active");
        tool("t_semantic", "按部门查询缺陷密度信息", List.of("缺陷密度"));

        SkillToolOverlapService service = new SkillToolOverlapService(skillRoutingRepo, skillIndexRepo,
                toolRoutingRepo, cache, skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
        SkillToolOverlapService.OverlapReport report = service.report();
        assertFalse(report.degraded());
        List<RoutingOverlapView> items = report.items();
        // cosine >= 0.88 -> HIGH
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).cosine() >= 0.88);
    }
}
