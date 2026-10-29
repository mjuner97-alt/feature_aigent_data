package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.dimension.DimensionAlias;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 词条 × 维度实体名撞车审计（检索关键词与维度值冲突治理方案.md §5）：HIGH/MED/SKIP + fail-open。 */
class SkillDimensionCollisionServiceTest {

    private static DimensionAlias alias(PeerDimensionType type, String word, String standard) {
        return new DimensionAlias(1L, type, word, standard, null, true, null);
    }

    private static SkillRoutingMetadata skill(String name, List<String> l1) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of(), List.of(), List.of(),
                "", true, null, l1, List.of(), List.of(), null, null);
    }

    private static PersonLedgerRepository ledger() {
        PersonLedgerRepository mock = org.mockito.Mockito.mock(PersonLedgerRepository.class);
        org.mockito.Mockito.when(mock.dimensionTruthDictionary(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Set.of());
        return mock;
    }

    @Test
    void termEqualToAliasWordIsHigh() {
        // HIGH 仅当词条完全等于 alias 口语词
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(skill("s1", List.of("风险组"))),
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        List<SkillDimensionCollisionService.CollisionRow> rows = service.auditAll();
        assertEquals(1, rows.size());
        assertEquals("HIGH", rows.get(0).level());
        assertEquals("风险组", rows.get(0).keyword());
        assertEquals("alias:TEAM", rows.get(0).entitySource());
        assertEquals("skill", rows.get(0).kind());
    }

    @Test
    void termSubstringOfEntityNameIsMed() {
        // 风险 不是口语词本身, 但是 风险资产业务组(标准名) 的子串 → MED
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(skill("s1", List.of("风险"))),
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "研发组", "风险资产业务组")),
                () -> ledger());
        List<SkillDimensionCollisionService.CollisionRow> rows = service.auditAll();
        assertEquals(1, rows.size());
        assertEquals("MED", rows.get(0).level());
        assertEquals("standard", rows.get(0).entitySource());
    }

    @Test
    void truthValueCollisionIsMed() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(skill("s1", List.of("个贷汽车"))),
                List::of,
                List::of,
                () -> {
                    PersonLedgerRepository mock = org.mockito.Mockito.mock(PersonLedgerRepository.class);
                    org.mockito.Mockito.when(mock.dimensionTruthDictionary("统计组"))
                            .thenReturn(java.util.Set.of("个贷汽车场景建设组"));
                    return mock;
                });
        List<SkillDimensionCollisionService.CollisionRow> rows = service.auditAll();
        assertEquals(1, rows.size());
        assertEquals("MED", rows.get(0).level());
        assertEquals("truth:统计组", rows.get(0).entitySource());
    }

    @Test
    void regexMetaTermIsSkipped() {
        // 含正则元字符的词条不可静态分析, 不出记录
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(skill("s1", List.of("风险(组)?"))),
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        assertTrue(service.auditAll().isEmpty());
    }

    @Test
    void noCollisionNoRow() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(skill("s1", List.of("缺陷密度"))),
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        assertTrue(service.auditAll().isEmpty());
    }

    @Test
    void inactiveSkillExcluded() {
        SkillRoutingMetadata inactive = new SkillRoutingMetadata("s1", "摘要", List.of(), List.of(),
                List.of(), "", false, null, List.of("风险"), List.of(), List.of(), null, null);
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> List.of(inactive),
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        assertTrue(service.auditAll().isEmpty());
    }

    @Test
    void bypassWordProducesBypassKindRow() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                List::of,
                () -> List.of(new SkillBypassKeyword(1L, "分析类", "风险组", true, "")),
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        List<SkillDimensionCollisionService.CollisionRow> rows = service.auditAll();
        assertEquals(1, rows.size());
        assertEquals("bypass", rows.get(0).kind());
        assertEquals("HIGH", rows.get(0).level());
    }

    @Test
    void auditAllFailsOpenToEmptyReport() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> { throw new RuntimeException("db down"); },
                List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        assertTrue(service.auditAll().isEmpty(), "fail-open: 词表失败 = 空报告, 不阻断");
    }

    @Test
    void auditSkillReportsFourLayerTerms() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                List::of, List::of,
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> ledger());
        List<SkillDimensionCollisionService.CollisionRow> rows =
                service.auditSkill("s1", List.of("达标率"), List.of("风险组"), List.of("风险项"), List.of());
        assertEquals(1, rows.size());
        assertEquals("HIGH", rows.get(0).level());
        assertEquals("风险组", rows.get(0).keyword());
    }

    @Test
    void runnerRunDoesNotThrow() {
        SkillDimensionCollisionService service = new SkillDimensionCollisionService(
                () -> { throw new RuntimeException("db down"); },
                List::of, List::of, () -> ledger());
        service.run(null);
    }
}
