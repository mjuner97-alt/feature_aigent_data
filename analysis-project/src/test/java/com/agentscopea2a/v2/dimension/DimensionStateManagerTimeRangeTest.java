package com.agentscopea2a.v2.dimension;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * timeRange 注入 dimension_context 单测（docs/202611/dimension_context维度上下文机制解析.md §12）：
 * 锚点提取入状态（拍板④ LLM 路径）、同轮并存/跨轮清掉（拍板②）、多范围（拍板③）、
 * 歧义短路反问 + 一次性回填（拍板①）。
 */
class DimensionStateManagerTimeRangeTest {

    /** timeRange 词表行（业务自配，拍板⑤）：本周/当周 → THIS_WEEK 等锚点。 */
    private static final DimensionKeyConfig TIME_RANGE_ROW = new DimensionKeyConfig(
            1L, "timeRange", "时间范围", false,
            List.of(new DimensionKeyConfig.AliasMapping("本周", "THIS_WEEK"),
                    new DimensionKeyConfig.AliasMapping("当周", "THIS_WEEK"),
                    new DimensionKeyConfig.AliasMapping("上周", "LAST_WEEK"),
                    new DimensionKeyConfig.AliasMapping("今天", "TODAY"),
                    new DimensionKeyConfig.AliasMapping("近7天", "LAST:7d")),
            true, null);

    private final DimensionStateManager manager = new DimensionStateManager(
            null, null, null, () -> List.of(TIME_RANGE_ROW));

    private RuntimeContext ctx() {
        return RuntimeContext.builder().sessionId("t-tr").userId("u").build();
    }

    private List<String> extractAnchors(String question) {
        return manager.analyzeQuestionRuleBased(question)
                .getExplicitDimensions().getTimeRangeAnchors();
    }

    // ==================== 提取层（拍板④：与直接执行同源同口径） ====================

    @Test
    void vocabularyAndRegexAnchorsExtracted() {
        // 内置正则轨：近N天 → LAST:nd（2026-11-17 口径：含今天）
        assertEquals(List.of("LAST:30d"), extractAnchors("近30天的缺陷数"));
        // 歧义轨：裸近N个月 → AMBIGUOUS:MONTHS:n（方向反问）
        assertEquals(List.of("AMBIGUOUS:MONTHS:3"), extractAnchors("近3个月的缺陷数"));
        // 连写让位（换轨拍板⑷）："近3个月版本" 归版本键，不产 timeRange
        assertTrue(extractAnchors("近3个月版本的缺陷数").isEmpty());
    }

    @Test
    void dateRangeAndBareDateAnchors() {
        assertTrue(extractAnchors("10月1日到10月15日的缺陷数")
                .contains("DATE:" + java.time.LocalDate.now().getYear() + "-10-01~"
                        + java.time.LocalDate.now().getYear() + "-10-15"));
    }

    // ==================== 状态落槽 + 继承/重置（拍板②） ====================

    @Test
    void anchorOnlyQuestionLandsInStateAndRenders() {
        // level 漏判（"本周"不触发任何 level 正则）但显式锚点非空 → 仍落状态（hasAnyDimension 含锚点）
        RuntimeContext ctx = ctx();
        DimensionStateManager.ProcessResult r1 = manager.processQuestionInContext(ctx, "本周的缺陷数");
        assertEquals(List.of("THIS_WEEK"), r1.newState().getTimeRangeAnchors());
        assertTrue(r1.newState().hasDimensions(), "仅锚点也应触发 dimension_context 注入");
        // 指纹/等值不含锚点（日期随今天漂移，绝不进缓存键）
        assertEquals("", r1.newState().toCacheKey());
        DimensionState other = r1.newState().deepCopy();
        other.setTimeRangeAnchors(List.of("LAST:1M"));
        assertEquals(r1.newState(), other, "equals 不含 timeRangeAnchors");
    }

    @Test
    void sameRoundAnchorAndVersionCoexist() {
        // 拍板② 同轮并存：「本周 F-A 的缺陷数，和10月版本的完成率对比」两槽各自有值互不抑制
        RuntimeContext ctx = ctx();
        DimensionStateManager.ProcessResult r = manager.processQuestionInContext(
                ctx, "本周 F-A 的缺陷数，和10月版本的完成率对比");
        assertNotNull(r.newState().getTimeDimension());
        assertEquals(DimensionState.TimeDimensionType.VERSION, r.newState().getTimeDimension().getType());
        assertEquals(List.of("THIS_WEEK"), r.newState().getTimeRangeAnchors());
        assertNotNull(r.newState().getPeerDimension());
    }

    @Test
    void explicitNewTimeClearsInheritedAnchors() {
        // 拍板② 跨轮清掉：「本周…」→ 次轮「11月版本的呢」显式新时间 → 旧区间不串值
        RuntimeContext ctx = ctx();
        manager.processQuestionInContext(ctx, "本周的缺陷数");
        DimensionStateManager.ProcessResult r2 = manager.processQuestionInContext(ctx, "11月版本的呢");
        assertEquals(DimensionState.TimeDimensionType.VERSION, r2.newState().getTimeDimension().getType());
        assertNull(r2.newState().getTimeRangeAnchors(), "显式新时间应清掉继承的 timeRange 锚点");
    }

    @Test
    void anchorsInheritWithTimeLayerOnLowerLevels() {
        RuntimeContext ctx = ctx();
        manager.processQuestionInContext(ctx, "本周的缺陷数");
        // DEPARTMENT 层级：timeDimension 与锚点同属时间层一起继承
        DimensionStateManager.ProcessResult r2 =
                manager.processQuestionInContext(ctx, "杭州开发一部明细呢");
        assertEquals(List.of("THIS_WEEK"), r2.newState().getTimeRangeAnchors());
        assertEquals(List.of("杭州开发一部"), r2.newState().getDepartments());
    }

    @Test
    void newAnchorReplacesInheritedAnchor() {
        RuntimeContext ctx = ctx();
        manager.processQuestionInContext(ctx, "本周的缺陷数");
        // TIME_RANGE 层级（"趋势"）继承锚点 + 本轮新锚点覆盖
        DimensionStateManager.ProcessResult r2 =
                manager.processQuestionInContext(ctx, "近7天的缺陷趋势");
        assertEquals(List.of("LAST:7d"), r2.newState().getTimeRangeAnchors());
    }

    @Test
    void multipleRangeAnchorsCollected() {
        // 拍板③：多范围全收集（渲染侧全渲染+标注，落参侧只收第一个——直接执行轨现状不动）
        RuntimeContext ctx = ctx();
        DimensionStateManager.ProcessResult r = manager.processQuestionInContext(
                ctx, "近3个月和近1个月的缺陷密度对比");
        assertEquals(List.of("AMBIGUOUS:MONTHS:3", "AMBIGUOUS:MONTHS:1"), r.newState().getTimeRangeAnchors());
    }

    // ==================== 歧义短路反问 + 一次性回填（拍板①） ====================

    @Test
    void ambiguousAnchorShortCircuitsWithAskPrompt() {
        String ask = manager.startClarificationIfAmbiguous("近3个月的缺陷数");
        assertNotNull(ask, "AMBIGUOUS:MONTHS 锚点应短路反问");
        assertTrue(ask.contains("历史") && ask.contains("未来"), ask);
    }

    @Test
    void deterministicAnchorsDoNotShortCircuit() {
        assertNull(manager.startClarificationIfAmbiguous("近30天的缺陷数"));
        assertNull(manager.startClarificationIfAmbiguous("10月1日到10月15日的缺陷数"));
    }

    @Test
    void consumeResolvesAnchorAndRestoresOriginalQuestion() {
        String original = "近3个月的缺陷数";
        assertNotNull(manager.startClarificationIfAmbiguous(original));

        String restored = manager.consumeClarification("历史");
        assertEquals(original, restored, "命中后返回原问题作为本轮问题");

        // 确认轮还原的问题不得再次短路（防回环）
        assertNull(manager.startClarificationIfAmbiguous(restored));

        // 中间件侧：重提取的歧义锚点被确定性锚点覆盖（一次性回填，用后即清）
        RuntimeContext ctx = ctx();
        DimensionStateManager.ProcessResult r = manager.processQuestionInContext(ctx, restored);
        assertEquals(List.of("LAST:3M"), r.newState().getTimeRangeAnchors());
    }

    @Test
    void consumeFutureDirectionMapsToNext() {
        assertNotNull(manager.startClarificationIfAmbiguous("近2个月的缺陷数"));
        assertEquals("近2个月的缺陷数", manager.consumeClarification("未来"));
        RuntimeContext ctx = ctx();
        DimensionStateManager.ProcessResult r =
                manager.processQuestionInContext(ctx, "近2个月的缺陷数");
        assertEquals(List.of("NEXT:2M"), r.newState().getTimeRangeAnchors());
    }

    @Test
    void missDropsTimeRangePending() {
        assertNotNull(manager.startClarificationIfAmbiguous("近3个月的缺陷数"));
        assertNull(manager.consumeClarification("今天天气怎么样"), "答非所问丢弃 pending");
        // 丢弃后同一 pending 不可再次消费
        assertNull(manager.consumeClarification("历史"));
        // 无 pending 时普通问题不短路
        assertNull(manager.startClarificationIfAmbiguous("本周的缺陷数"));
    }
}
