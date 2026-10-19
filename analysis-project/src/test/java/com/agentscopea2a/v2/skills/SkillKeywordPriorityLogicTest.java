package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关键词优先级行模型存储（docs/202610/skill关键词优先级行模型存储方案.md v3）核心逻辑：
 * 展开（L1 不进索引、L2/L3 位次=行序、格内词同位次）+ v2 两级表/旧表迁移 + Controller 校验。
 */
class SkillKeywordPriorityLogicTest {

    // ── splitWords / normalizeCell ──────────────────────────────────────

    @Test
    void splitWordsHandlesAllSeparatorsAndTrims() {
        assertEquals(List.of("问题", "缺陷", "A号", "D号"), SkillKeywordPriorityRepository.splitWords("问题、缺陷，A号,D号"));
        assertTrue(SkillKeywordPriorityRepository.splitWords("  、，,  ").isEmpty());
        assertTrue(SkillKeywordPriorityRepository.splitWords(null).isEmpty());
    }

    @Test
    void normalizeCellDedupsPreservingOrder() {
        assertEquals("问题、缺陷", SkillKeywordPriorityRepository.normalizeCell("问题、缺陷、问题"));
    }

    // ── expand ───────────────────────────────────────────────────────────

    @Test
    void expandOmitsLevel1AndRanksByRowOrder() {
        List<SkillKeywordPriorityLine> lines = List.of(
                line("问题、缺陷、A号、D号、DCM、适应性", "遗留", ""),
                line("问题、缺陷、A号、D号、DCM、适应性", "未闭环、开口、在途、未关闭", ""),
                line("问题、缺陷、A号、D号、DCM、适应性", "长时效", "严重、超7天"));

        List<SkillKeywordPriority> expanded = SkillKeywordPriorityRepository.expand(lines);

        // L1 词全部不进索引
        assertFalse(expanded.stream().anyMatch(r -> r.level() == 1));
        // L2/L3 词位次 = 行序；同格内所有词同位次
        assertEquals(8, expanded.size());
        assertEquals(rankOf(expanded, 2, "遗留"), 0);
        assertEquals(rankOf(expanded, 2, "未闭环"), 1);
        assertEquals(rankOf(expanded, 2, "开口"), 1);
        assertEquals(rankOf(expanded, 2, "在途"), 1);
        assertEquals(rankOf(expanded, 2, "未关闭"), 1);
        assertEquals(rankOf(expanded, 3, "严重"), 2);
        assertEquals(rankOf(expanded, 3, "超7天"), 2);
    }

    @Test
    void expandRanksPerL1Group() {
        // 位次 = L1 分组内行序：Qi 组从 0 重新起算；同组穿插出现时组内计数延续
        List<SkillKeywordPriorityLine> lines = List.of(
                line("问题、缺陷", "遗留", ""),
                line("问题、缺陷", "未闭环", ""),
                line("Qi、卡口", "达标率", ""),
                line("问题、缺陷", "流转", ""));
        List<SkillKeywordPriority> expanded = SkillKeywordPriorityRepository.expand(lines);

        assertEquals(0, rankOf(expanded, 2, "遗留"));
        assertEquals(1, rankOf(expanded, 2, "未闭环"));
        assertEquals(0, rankOf(expanded, 2, "达标率"));
        assertEquals(2, rankOf(expanded, 2, "流转"));
    }

    @Test
    void expandSkipsBlankCellsAndNullLines() {
        List<SkillKeywordPriority> expanded = SkillKeywordPriorityRepository.expand(
                java.util.Arrays.asList(null, line("Qi、卡口", "  ", "到期")));
        assertEquals(1, expanded.size());
        assertEquals(3, expanded.get(0).level());
        assertEquals("到期", expanded.get(0).keyword());
        assertEquals(0, expanded.get(0).sortOrder());
    }

    private static int rankOf(List<SkillKeywordPriority> rows, int level, String keyword) {
        return rows.stream()
                .filter(r -> r.level() == level && keyword.equals(r.keyword()))
                .mapToInt(SkillKeywordPriority::sortOrder)
                .findFirst().orElseThrow();
    }

    // ── v2ToLines（两级表迁移） ──────────────────────────────────────────

    @Test
    void v2TablesFlattenByGroupThenRowOrder() {
        List<SkillKeywordPriorityRepository.V2GroupRow> groups = List.of(
                new SkillKeywordPriorityRepository.V2GroupRow(7, "问题、缺陷", 0),
                new SkillKeywordPriorityRepository.V2GroupRow(8, "Qi、卡口", 1));
        List<SkillKeywordPriorityRepository.V2LineRow> rows = List.of(
                new SkillKeywordPriorityRepository.V2LineRow(8, "达标率", "", 1),
                new SkillKeywordPriorityRepository.V2LineRow(7, "遗留", "", 0),
                new SkillKeywordPriorityRepository.V2LineRow(8, "后评估", "", 0),
                new SkillKeywordPriorityRepository.V2LineRow(7, "未闭环", "", 1));

        List<SkillKeywordPriorityLine> lines = SkillKeywordPriorityRepository.v2ToLines(groups, rows);

        assertEquals(4, lines.size());
        assertEquals("问题、缺陷", lines.get(0).level1Keywords());
        assertEquals("遗留", lines.get(0).level2Keywords());
        assertEquals("未闭环", lines.get(1).level2Keywords());
        assertEquals("Qi、卡口", lines.get(2).level1Keywords());
        assertEquals("后评估", lines.get(2).level2Keywords());   // 组内按 row_order
        assertEquals("达标率", lines.get(3).level2Keywords());
    }

    @Test
    void v2EmptyGroupsYieldNoLines() {
        assertTrue(SkillKeywordPriorityRepository.v2ToLines(List.of(),
                List.of(new SkillKeywordPriorityRepository.V2LineRow(1, "遗留", "", 0))).isEmpty());
        assertTrue(SkillKeywordPriorityRepository.v2ToLines(null, null).isEmpty());
    }

    // ── legacyToLines（旧一词一行表迁移） ────────────────────────────────

    @Test
    void legacyRowsAggregateByIdenticalL1() {
        // 复刻现网 12 行配置的旧表形态（R1~R6 同 L1、R7~R12 同 L1）
        List<SkillKeywordPriorityRepository.LegacyRow> rows = List.of(
                row(1, "R1", "问题"), row(1, "R1", "缺陷"), row(1, "R1", "A号"),
                row(1, "R2", "问题"), row(1, "R2", "缺陷"), row(1, "R2", "A号"),
                row(2, "R1", "遗留"),
                row(2, "R2", "未闭环"), row(2, "R2", "开口"),
                row(1, "R7", "Qi"), row(1, "R7", "卡口"),
                row(1, "R8", "Qi"), row(1, "R8", "卡口"),
                row(2, "R7", "达标率"), row(2, "R7", "通过率"),
                row(2, "R8", "后评估"));

        List<SkillKeywordPriorityLine> lines = SkillKeywordPriorityRepository.legacyToLines(rows);

        assertEquals(4, lines.size());
        assertEquals("问题、缺陷、A号", lines.get(0).level1Keywords());
        assertEquals("遗留", lines.get(0).level2Keywords());
        assertEquals("未闭环、开口", lines.get(1).level2Keywords());
        assertEquals("Qi、卡口", lines.get(2).level1Keywords());
        assertEquals("达标率、通过率", lines.get(2).level2Keywords());
        assertEquals("后评估", lines.get(3).level2Keywords());
    }

    @Test
    void legacyRowsWithNullParentOrL1OnlySkipped() {
        List<SkillKeywordPriorityRepository.LegacyRow> rows = List.of(
                row(1, "", "问题"), row(1, "", "缺陷"),   // bug 期残留：L1 无行号
                row(2, "R9", "到期"),
                row(1, "R10", "放行"));                   // 只有 L1、无下钻的行
        assertTrue(SkillKeywordPriorityRepository.legacyToLines(rows).isEmpty());
    }

    @Test
    void legacySameL1NonConsecutiveParentsStayInFirstSeenOrder() {
        List<SkillKeywordPriorityRepository.LegacyRow> rows = List.of(
                row(1, "R1", "问题"), row(2, "R1", "遗留"),
                row(1, "R3", "Qi"), row(2, "R3", "达标率"),
                row(1, "R2", "问题"), row(2, "R2", "未闭环"));
        List<SkillKeywordPriorityLine> lines = SkillKeywordPriorityRepository.legacyToLines(rows);
        assertEquals(3, lines.size());
        assertEquals(List.of("问题", "问题", "Qi"),
                lines.stream().map(SkillKeywordPriorityLine::level1Keywords).toList());
        assertEquals(List.of("遗留", "未闭环", "达标率"),
                lines.stream().map(SkillKeywordPriorityLine::level2Keywords).toList());
    }

    private static SkillKeywordPriorityRepository.LegacyRow row(int level, String parent, String keyword) {
        return new SkillKeywordPriorityRepository.LegacyRow(level, parent, keyword);
    }

    private static SkillKeywordPriorityLine line(String l1, String l2, String l3) {
        return new SkillKeywordPriorityLine(null, l1, l2, l3, null);
    }

    // ── Controller 校验 ─────────────────────────────────────────────────

    @Test
    void controllerRejectsBlankLine() {
        SkillKeywordPriorityController controller = controller(true);
        List<SkillKeywordPriorityLine> lines = List.of(
                line("问题", "遗留", ""),
                line("问题", "  ", "、"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.replaceAll("admin", lines));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void controllerRejectsLineWithoutL1() {
        SkillKeywordPriorityController controller = controller(true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.replaceAll("admin", List.of(line("、，", "遗留", ""))));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void controllerRejectsNonAdmin() {
        SkillKeywordPriorityController controller = controller(false);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.replaceAll("user", List.of(line("问题", "遗留", ""))));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void controllerAcceptsValidLinesAndSaves() {
        SkillKeywordPriorityRepository repository = mock(SkillKeywordPriorityRepository.class);
        AdminRoleService adminRoleService = mock(AdminRoleService.class);
        when(adminRoleService.isAdminUserId("admin")).thenReturn(true);
        SkillKeywordPriorityController controller = new SkillKeywordPriorityController(repository, adminRoleService);
        List<SkillKeywordPriorityLine> lines = List.of(
                line("问题", "遗留", ""),
                line("问题", "未闭环", "严重"));
        controller.replaceAll("admin", lines);
        verify(repository).replaceAll(lines);
    }

    private static SkillKeywordPriorityController controller(boolean isAdmin) {
        SkillKeywordPriorityRepository repository = mock(SkillKeywordPriorityRepository.class);
        AdminRoleService adminRoleService = mock(AdminRoleService.class);
        when(adminRoleService.isAdminUserId("admin")).thenReturn(isAdmin);
        when(adminRoleService.isAdminUserId("user")).thenReturn(false);
        return new SkillKeywordPriorityController(repository, adminRoleService);
    }
}
