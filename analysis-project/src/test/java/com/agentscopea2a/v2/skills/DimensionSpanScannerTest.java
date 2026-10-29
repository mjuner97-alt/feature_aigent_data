package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.dimension.DimensionAlias;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 维度实体 span 扫描器（检索关键词与维度值冲突治理方案.md §4.2）：S1-S4 来源 / 合并 / fail-open。 */
class DimensionSpanScannerTest {

    private static DimensionAlias alias(PeerDimensionType type, String word, String standard) {
        return new DimensionAlias(1L, type, word, standard, null, true, null);
    }

    @Test
    void aliasWordAndStandardNameProduceSpans() {
        // S1 口语 + S2 标准名; 组名 token (S3) 贪婪吞部门前缀, 全句合并为一个 span
        DimensionSpanScanner scanner = new DimensionSpanScanner(
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> null, 30000);
        List<int[]> spans = scanner.scan(SkillTermMatcher.normalize("杭州四部风险组和风险资产业务组"));

        assertTrue(DimensionSpanScanner.inSpans(4, 6, spans), "风险组 内的 风险 命中应有 span 覆盖");
        assertTrue(DimensionSpanScanner.inSpans(8, 10, spans), "标准名内的命中应有 span 覆盖");
        assertTrue(scanner.scan(SkillTermMatcher.normalize("杭州的问题")).isEmpty(), "无实体词无组名 → 无 span");
    }

    @Test
    void teamTokenShieldsWithoutAliasConfig() {
        // S3: 无 alias 配置, "XX组" token 兜底（贪婪吞部门前缀, 风险 仍在 span 内）
        DimensionSpanScanner scanner = new DimensionSpanScanner(List::of, () -> null, 30000);
        List<int[]> spans = scanner.scan("杭州四部风险组的问题");
        assertTrue(DimensionSpanScanner.inSpans(4, 6, spans));
    }

    @Test
    void truthDictionaryValuesProduceSpans() {
        // S2: 台账真值三列 distinct 值
        PersonLedgerRepository ledger = org.mockito.Mockito.mock(PersonLedgerRepository.class);
        org.mockito.Mockito.when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(java.util.Set.of("个贷汽车场景建设组"));
        org.mockito.Mockito.when(ledger.dimensionTruthDictionary("产品线"))
                .thenReturn(java.util.Set.of());
        org.mockito.Mockito.when(ledger.dimensionTruthDictionary("应用"))
                .thenReturn(null);
        DimensionSpanScanner scanner = new DimensionSpanScanner(List::of, () -> ledger, 30000);

        String q = SkillTermMatcher.normalize("个贷汽车场景建设组的达标率");
        List<int[]> spans = scanner.scan(q);
        assertFalse(spans.isEmpty());
        assertTrue(DimensionSpanScanner.inSpans(0, 2, spans), "真值组名开头两字应有 span 覆盖");
    }

    @Test
    void timeSpanShielded() {
        // S4: 宽时间形态
        DimensionSpanScanner scanner = new DimensionSpanScanner(List::of, () -> null, 30000);
        List<int[]> spans = scanner.scan("10月版本的达标率");
        assertTrue(DimensionSpanScanner.inSpans(0, 2, spans), "10月 版本词应有时间 span 覆盖");
    }

    @Test
    void singleCharWordsAreSkipped() {
        DimensionSpanScanner scanner = new DimensionSpanScanner(
                () -> List.of(alias(PeerDimensionType.TEAM, "组", "我")), () -> null, 30000);
        assertTrue(scanner.scan("组和我都出现").isEmpty(), "单字实体词不进 span 集");
    }

    @Test
    void overlappingSpansAreMerged() {
        // token"杭州四部风险组"(贪婪吞前缀) + alias 口语"风险组" 重叠 → 合并为一个区间
        DimensionSpanScanner scanner = new DimensionSpanScanner(
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> null, 30000);
        List<int[]> spans = scanner.scan("杭州四部风险组");
        assertEquals(1, spans.size());
        assertEquals(0, spans.get(0)[0]);
        assertEquals(7, spans.get(0)[1]);
    }

    @Test
    void aliasLoadFailureFailsOpenToEmptySpans() {
        // fail-open 仅作用于 DB 词表 span (S1/S2); S3 组名 token 是纯正则, 不依赖词表
        DimensionSpanScanner scanner = new DimensionSpanScanner(
                () -> { throw new RuntimeException("db down"); }, () -> null, 30000);
        assertTrue(scanner.scan("全球市场的问题").isEmpty(), "fail-open: 词表失败 = S1/S2 屏蔽关闭");
        assertFalse(scanner.scan("风险组的问题").isEmpty(), "S3 token 不依赖词表, 不受 fail-open 影响");
    }

    @Test
    void staleSnapshotUsedWhenRefreshFails() throws Exception {
        List<DimensionAlias> good = List.of(alias(PeerDimensionType.TEAM, "全球市场", "全球市场风险管理应用"));
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(false);
        DimensionSpanScanner scanner = new DimensionSpanScanner(() -> {
            if (fail.get()) throw new RuntimeException("db down");
            return good;
        }, () -> null, 10);

        assertFalse(scanner.scan("全球市场的问题").isEmpty());
        fail.set(true);
        Thread.sleep(40); // 跨过 TTL 触发刷新失败
        assertFalse(scanner.scan("全球市场的问题").isEmpty(), "刷新失败回退陈旧快照, 不清空屏蔽");
    }

    @Test
    void disabledScannerReturnsAlwaysEmpty() {
        DimensionSpanScanner scanner = new DimensionSpanScanner(
                () -> List.of(alias(PeerDimensionType.TEAM, "风险组", "风险资产业务组")),
                () -> null, 30000) {
            @Override
            public List<int[]> scan(String normalizedQuestion) {
                return List.of();
            }
        };
        assertTrue(scanner.scan("风险组的问题").isEmpty());
    }
}
