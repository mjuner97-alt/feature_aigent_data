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
        return skill(name, summary, keywords, List.of(), topicTags, description, status);
    }

    private SkillRoutingMetadata skill(String name, String summary, List<String> keywords,
                                       List<String> domainTags, List<String> topicTags,
                                       String description, String status) {
        SkillRoutingMetadata metadata = new SkillRoutingMetadata(
                name, summary, keywords, domainTags, topicTags, "u_skill", true, null);
        when(skillRoutingRepo.findActive()).thenReturn(List.of(metadata));
        when(skillIndexRepo.findByName(name)).thenReturn(java.util.Optional.of(new SkillEntry(
                name, null, description, 1, 0, null, status, "user_generated", "u_skill", null)));
        when(skillRoutingRepo.creatorForSkill(name)).thenReturn("u_skill");
        return metadata;
    }

    private void tool(String toolId, String description, List<String> topicTags) {
        tool(toolId, description, topicTags, List.of());
    }

    private void tool(String toolId, String description, List<String> topicTags, List<String> dimensionTags) {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(new ToolRoutingMetadata(
                toolId, ToolRoutingToolType.SCRIPT, description, topicTags, List.of(), dimensionTags,
                0, true, null)));
    }

    /** embedding stub: 声明含"报告"映射 {1,1}, 工具描述不含则映射 {1,4}, cosine ≈ 0.857 (落在 0.80~0.88 区间)。 */
    private EmbeddingClient mediumCosineStub() {
        return new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                if (text != null && text.contains("报告")) return new float[] {1f, 1f};
                return new float[] {1f, 4f};
            }

            @Override
            public int dimension() {
                return 2;
            }
        };
    }

    private SkillToolOverlapService nonDegradedService(EmbeddingClient client) throws Exception {
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(client, skillDescriptionSource, null);
        cache.startWarmup();
        long deadline = System.currentTimeMillis() + 2000;
        while (!cache.warm() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return new SkillToolOverlapService(skillRoutingRepo, skillIndexRepo, toolRoutingRepo,
                cache, skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
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
    void topicTagOverlapNoLongerProducesRecords() {
        // §6.3 定版: tool 四层标签不参与与 skill 的层比较; skill topicTags 已不参与路由,
        // 仅共享业务主题且无 keywords/佐证/S3 命中的对不出记录 (原 LOW 巡检级取消)。
        skill("page_1", "按部门统计质量分和达标率", List.of(),
                List.of("质量分", "达标率"), "按部门统计质量分和达标率", "active");
        tool("t1", "按部门计算质量分和达标率", List.of("质量分", "达标率"));
        assertTrue(degradedService().report().items().isEmpty());
    }

    @Test
    void keywordEvidenceAloneDoesNotProduceRecord() {
        // S1' 只佐证: keywords 命中 tool 描述但无 S2 (degraded) 且无 S3 命中 -> 不出记录
        skill("page_1", "按部门统计缺陷密度完成情况", List.of("缺陷密度"),
                List.of(), "按部门统计缺陷密度完成情况", "active");
        tool("t_stat", "按部门计算缺陷密度指标", List.of());
        assertTrue(degradedService().report().items().isEmpty());
    }

    @Test
    void mediumRequiresKeywordCorroboration() throws Exception {
        // cosine ≈ 0.857 落在 [0.80, 0.88): 有 keywords 佐证 -> MEDIUM; 无任何佐证 -> 不出记录
        skill("page_1", "按部门统计缺陷密度并生成报告", List.of("缺陷密度"),
                List.of(), "按部门统计缺陷密度并生成报告", "active");
        tool("t_medium", "按部门统计缺陷密度工单数据", List.of());

        List<RoutingOverlapView> items = nonDegradedService(mediumCosineStub()).report().items();
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).cosine() >= 0.80 && items.get(0).cosine() < 0.88);
        assertEquals(List.of("缺陷密度"), items.get(0).keywordEvidence());

        // 无佐证: 同样的 cosine 区间不出记录 (原 LOW 取消)
        skill("page_1", "按部门统计缺陷密度并生成报告", List.of(),
                List.of(), "按部门统计缺陷密度并生成报告", "active");
        assertTrue(nonDegradedService(mediumCosineStub()).report().items().isEmpty());
    }

    @Test
    void domainDimensionDirectedPairingCountsAsEvidence() throws Exception {
        // domain↔dimension 定向配对: skill 领域标签命中 tool 维度标签也算 S1' 命中
        skill("page_1", "统计各部门维度的完成情况并生成报告", List.of(),
                List.of("部门"), List.of(), "统计各部门维度的完成情况并生成报告", "active");
        tool("t_dim", "统计各部门维度的完成情况数据", List.of(), List.of("部门", "版本计划"));

        List<RoutingOverlapView> items = nonDegradedService(mediumCosineStub()).report().items();
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertEquals(List.of("部门"), items.get(0).keywordEvidence());

        // 定向配对不通用: 维度标签无交集时不命中, 同 cosine 区间不出记录
        tool("t_dim", "统计各部门维度的完成情况数据", List.of(), List.of("应用", "版本计划"));
        assertTrue(nonDegradedService(mediumCosineStub()).report().items().isEmpty());
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
