package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillRoutingMetadata;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSkillOverlapServiceTest {

    private SkillRoutingMetadataRepository skillRoutingRepo;
    private SkillIndexRepository skillIndexRepo;
    private SkillDescriptionSource skillDescriptionSource;

    @BeforeEach
    void setUp() {
        skillRoutingRepo = mock(SkillRoutingMetadataRepository.class);
        skillIndexRepo = mock(SkillIndexRepository.class);
        skillDescriptionSource = mock(SkillDescriptionSource.class);
        when(skillRoutingRepo.findAll()).thenReturn(List.of());
        when(skillDescriptionSource.allActiveSkills()).thenReturn(List.of());
    }

    private SkillRoutingMetadata skill(String skillName, String shortSummary,
                                       List<String> keywords, List<String> domainTags, boolean active) {
        return new SkillRoutingMetadata(skillName, shortSummary, keywords, domainTags,
                List.of(), "alice", active, LocalDateTime.of(2026, 10, 10, 0, 0));
    }

    private void stubManageRow(String skillName, String description) {
        when(skillDescriptionSource.allActiveSkills()).thenReturn(List.of(
                new SkillDescriptionSource.SkillDescriptionRow(
                        1L, skillName, description, "alice", skillName, "PUBLIC")));
    }

    /** 联合存活判定要求 manageRow 或 indexActive 至少一个, 默认给每个 skill 补 manage 行。 */
    private void stubManageRows(String... skillNames) {
        java.util.List<SkillDescriptionSource.SkillDescriptionRow> rows = new java.util.ArrayList<>();
        long id = 1;
        for (String name : skillNames) {
            rows.add(new SkillDescriptionSource.SkillDescriptionRow(
                    id++, name, name + " 的管理页描述", "alice", name, "PUBLIC"));
        }
        when(skillDescriptionSource.allActiveSkills()).thenReturn(rows);
    }

    private SkillSkillOverlapService degradedService() {
        // EmbeddingClient=null -> semanticAvailable=false -> degraded (cosine 不可算)
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, skillDescriptionSource, null);
        cache.startWarmup();
        return new SkillSkillOverlapService(skillRoutingRepo, skillIndexRepo, cache,
                skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
    }

    /** 非降级服务: 按描述文本映射到固定向量, cosine 由测试方控制。 */
    private SkillSkillOverlapService liveService(Map<String, float[]> vectorByText) {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embed(anyString())).thenAnswer(inv ->
                vectorByText.getOrDefault((String) inv.getArgument(0), new float[] {1f, 0f}));
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(embedding, skillDescriptionSource, null);
        cache.startWarmup();
        awaitWarm(cache);
        return new SkillSkillOverlapService(skillRoutingRepo, skillIndexRepo, cache,
                skillDescriptionSource, 0.80, 0.88, 0.85, 300000);
    }

    private static void awaitWarm(GovernanceEmbeddingCache cache) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cache.warm() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(cache.warm(), "embedding cache warmup timed out");
    }

    private static List<SkillSkillOverlapView> report(SkillSkillOverlapService service) {
        return service.report().items();
    }

    @Test
    void disjointEntryTermsNeverOverlapEvenWithIdenticalDescriptions() {
        // T0 前提: 入口词无相等/包含关系 -> 即使描述完全相同也不出记录
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷统计"), List.of("质检"), true),
                skill("skill_beta", "描述甲", List.of("交付进度"), List.of("交付"), true)));
        SkillSkillOverlapService service = liveService(Map.of());

        assertTrue(report(service).isEmpty());
    }

    @Test
    void bothSidesWithoutKeywordsProduceNoRecord() {
        // 双方入口词均空 (keywords 空, 名称归一化后不共现) -> 永远不会同时入候选
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of(), List.of(), true),
                skill("skill_beta", "描述甲", List.of(), List.of(), true)));
        SkillSkillOverlapService service = liveService(Map.of());

        assertTrue(report(service).isEmpty());
    }

    @Test
    void threeLayersAllSameIsHigh() {
        stubManageRows("skill_alpha", "skill_beta");
        // keywords/domain 集合相等 + cosine 0.93 -> HIGH, differingLayers 为空
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷", "统计"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷", "统计"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).differingLayers().isEmpty());
        assertTrue(items.get(0).suggestion().contains("合并为一个 Skill"));
        assertTrue(items.get(0).activeA());
        assertTrue(items.get(0).activeB());
    }

    @Test
    void intersectingButUnequalDomainTagsCapAtMedium() {
        stubManageRows("skill_alpha", "skill_beta");
        // 生产复现: keywords 相等 + 描述全同 (cosine=1.0) + domainTags 相交但不同 -> MEDIUM
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检", "交付"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("领域标签"));
        assertTrue(items.get(0).suggestion().contains("领域标签不同"));
        assertTrue(items.get(0).suggestion().contains("质检、交付"));
    }

    @Test
    void keywordDifferenceCapsAtMediumEvenWithIdenticalDescriptions() {
        stubManageRows("skill_alpha", "skill_beta");
        // domain 相等 + 描述全同 + keywords 不等 (T0 经 "缺陷" 相等命中) -> 差异层=关键词
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷", "统计"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷", "趋势"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("关键词"));
        assertTrue(items.get(0).suggestion().contains("关键词不同"));
        assertTrue(items.get(0).keywordOverlap().contains("缺陷"));
    }

    @Test
    void keywordContainmentSatisfiesCooccurrencePremise() {
        stubManageRows("skill_alpha", "skill_beta");
        // T0 包含关系: "缺陷密度" 问题含 "缺陷" -> 长词⊃短词, 二者同时命中
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷密度"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        // 关键词集合不等 (缺陷密度 vs 缺陷) -> 差异层=关键词, 描述全同封顶 MEDIUM
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("关键词"));
        assertTrue(items.get(0).keywordOverlap().contains("缺陷密度⊃缺陷"));
    }

    @Test
    void mediumCosineWithAllTagsSameCapsAtMedium() {
        stubManageRows("skill_alpha", "skill_beta");
        // cosine 0.85 (0.80~0.88) + 三层标签全同 -> MEDIUM (描述"相近但有差异", 不升 HIGH)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷", "统计"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷", "统计"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {0.85f, (float) Math.sqrt(1 - 0.85 * 0.85)}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("描述"));
        assertTrue(items.get(0).cosineKnown());
    }

    @Test
    void degradedHardEvidenceRequiresSimilarNames() {
        stubManageRows("quality_report", "quality_report2");
        // degraded 下 L1∧L2 相等 + 名称高度相近 (levenshtein ≥ 0.9) -> HIGH (名称硬证据)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("quality_report", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("quality_report2", "描述乙", List.of("缺陷"), List.of("质检"), true)));

        List<SkillSkillOverlapView> items = report(degradedService());
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertFalse(items.get(0).cosineKnown());
        assertTrue(items.get(0).suggestion().contains("仅名称判定"));
    }

    @Test
    void degradedWithoutNameEvidenceDoesNotProduceHighOrMedium() {
        // degraded + L1∧L2 相等 + 名称不相近 + 单一共现词 -> 无 HIGH/MEDIUM (keywords 相等共现词=1, 连 LOW 也不出)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));

        assertTrue(report(degradedService()).isEmpty());
    }

    @Test
    void similarNamesAloneAreLow() {
        stubManageRows("query_list", "query_lisp");
        // T0 未命中 (名称相近但无包含关系, keywords 无共现), 仅名称相似 -> LOW 命名卫生
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("query_list", "描述甲", List.of("缺陷统计"), List.of("质检"), true),
                skill("query_lisp", "描述乙", List.of("交付进度"), List.of("交付"), true)));
        SkillSkillOverlapService service = liveService(Map.of());

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("LOW", items.get(0).level());
        assertTrue(items.get(0).suggestion().contains("命名卫生"));
    }

    @Test
    void cooccurrenceWithDissimilarDescriptionIsLowPatrol() {
        stubManageRows("skill_alpha", "skill_beta");
        // T0 共现词 ≥ 2 + cosine 低 + 名称不相近 -> LOW 巡检
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷", "统计"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷", "统计"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {0f, 1f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("LOW", items.get(0).level());
    }

    @Test
    void bothInactivePairsFilteredButSingleActiveReported() {
        stubManageRows("skill_alpha", "skill_beta");
        // 双方均未 active 的对是纯噪音不报
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), false),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), false)));
        assertTrue(report(degradedService()).isEmpty());

        // alpha 变 active: alpha/beta 对 (beta 未 active) 照报, active 状态如实
        // (启用互斥需要看到"本侧未 active、对侧已 active"的对)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), false)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));
        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).activeA());
        assertFalse(items.get(0).activeB());
    }

    @Test
    void blacklistedSkillIsExcludedFromScope() {
        stubManageRows("skill_beta");
        // skill_index 黑名单 = 软删标记, 必须排除 (联合存活判定回归)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        when(skillIndexRepo.findByName("skill_alpha")).thenReturn(Optional.of(entry("skill_alpha", "blacklist")));

        assertTrue(report(degradedService()).isEmpty());
    }

    @Test
    void declarationFallsBackToManageRowDescription() {
        // shortSummary 为空 -> declaration 回退 skill_manage.description (向量按回退文本取)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        when(skillDescriptionSource.allActiveSkills()).thenReturn(List.of(
                new SkillDescriptionSource.SkillDescriptionRow(
                        1L, "skill_alpha", "管理页权威描述", "alice", "skill_alpha", "PUBLIC"),
                new SkillDescriptionSource.SkillDescriptionRow(
                        2L, "skill_beta", "管理页描述乙", "alice", "skill_beta", "PUBLIC")));
        SkillSkillOverlapService service = liveService(Map.of(
                "管理页权威描述", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<SkillSkillOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
    }

    @Test
    void invalidateClearsCache() {
        stubManageRows("skill_alpha", "skill_beta");
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷统计"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("交付进度"), List.of("交付"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));
        assertTrue(report(service).isEmpty());

        // 元数据变化后 invalidate -> 重新计算 (keywords 改成相等)
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        service.invalidate();

        assertEquals("HIGH", report(service).get(0).level());
    }

    @Test
    void summaryCountsHighOnBothSides() {
        stubManageRows("skill_alpha", "skill_beta");
        when(skillRoutingRepo.findAll()).thenReturn(List.of(
                skill("skill_alpha", "描述甲", List.of("缺陷"), List.of("质检"), true),
                skill("skill_beta", "描述乙", List.of("缺陷"), List.of("质检"), true)));
        SkillSkillOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        SkillSkillOverlapService.OverlapSummary summary = service.summary();
        assertEquals(1, summary.high());
        assertEquals(1L, summary.highBySkill().get("skill_alpha"));
        assertEquals(1L, summary.highBySkill().get("skill_beta"));
    }

    private static SkillEntry entry(String name, String status) {
        return new SkillEntry(name, "fp", "desc", 1, 0,
                LocalDateTime.of(2026, 10, 10, 0, 0), status, "user_generated", "alice",
                LocalDateTime.of(2026, 10, 10, 0, 0));
    }
}
