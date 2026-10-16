package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检索旁路判定（§8.3）: 词条命中 → 显式点名豁免 → 容错不旁路（拍板①④⑤⑦）。 */
class SkillBypassKeywordServiceTest {

    private static final List<SkillBypassKeyword> SEED_LIKE = List.of(
            new SkillBypassKeyword(1L, "分析类", "分析", true, ""),
            new SkillBypassKeyword(2L, "表格类", "表格", true, ""),
            new SkillBypassKeyword(3L, "质量板块", "缺陷密度", true, ""));

    private static SkillRoutingMetadata activeSkill(String name) {
        return new SkillRoutingMetadata(name, name + " 摘要", List.of("达标率"), List.of(), List.of(),
                "", true, LocalDateTime.now());
    }

    private static SkillBypassKeywordService service(List<SkillBypassKeyword> keywords,
                                                     List<SkillRoutingMetadata> active) {
        return new SkillBypassKeywordService(() -> keywords, () -> active);
    }

    @Test
    void bypassWordHitDisablesRetrieval() {
        SkillBypassKeywordService svc = service(SEED_LIKE, List.of(activeSkill("q2_metrics")));
        assertTrue(svc.bypassed("帮我分析一下各部门的达标率"));
        assertTrue(svc.bypassed("缺陷密度生成表格"));
        assertFalse(svc.bypassed("杭州二部本版本达标率"));
    }

    @Test
    void explicitSkillNameExemptsFromBypass() {
        SkillBypassKeywordService svc = service(SEED_LIKE, List.of(activeSkill("q2_metrics")));
        assertFalse(svc.bypassed("请使用 q2_metrics 分析各部门达标率"));
    }

    @Test
    void inactiveSkillNameDoesNotExempt() {
        SkillRoutingMetadata inactive = new SkillRoutingMetadata("retired_skill", "已停用",
                List.of(), List.of(), List.of(), "", false, LocalDateTime.now());
        SkillBypassKeywordService svc = service(SEED_LIKE, List.of(inactive, activeSkill("q2_metrics")));
        assertTrue(svc.bypassed("请使用 retired_skill 分析达标率"));
    }

    @Test
    void emptyOrBlankQuestionNeverBypasses() {
        SkillBypassKeywordService svc = service(SEED_LIKE, List.of());
        assertFalse(svc.bypassed(null));
        assertFalse(svc.bypassed(""));
        assertFalse(svc.bypassed("   "));
    }

    @Test
    void emptyKeywordTableMeansFeatureOff() {
        SkillBypassKeywordService svc = service(List.of(), List.of());
        assertFalse(svc.bypassed("帮我分析达标率"));
    }

    @Test
    void regexTermSemanticsMatchesSelector() {
        // 拍板⑤/⑦: 词条可编译即正则, lookaround 排除否定/限定语境误伤
        List<SkillBypassKeyword> lookaround = List.of(
                new SkillBypassKeyword(1L, "分析类", "(?<!数据)分析", true, ""));
        SkillBypassKeywordService svc = service(lookaround, List.of());
        assertFalse(svc.bypassed("查询数据分析结果"));
        assertTrue(svc.bypassed("帮我分析一下趋势"));
    }

    @Test
    void invalidRegexFallsBackToLiteralContains() {
        List<SkillBypassKeyword> invalid = List.of(
                new SkillBypassKeyword(1L, "表格类", "[", true, ""));
        SkillBypassKeywordService svc = service(invalid, List.of());
        assertTrue(svc.bypassed("数组[0]取值报错"));
        assertFalse(svc.bypassed("普通问题"));
    }

    @Test
    void supplierFailureMeansNotBypassing() {
        SkillBypassKeywordService svc = new SkillBypassKeywordService(
                () -> { throw new IllegalStateException("db down"); },
                () -> List.of());
        assertFalse(svc.bypassed("帮我分析达标率"));
    }
}
