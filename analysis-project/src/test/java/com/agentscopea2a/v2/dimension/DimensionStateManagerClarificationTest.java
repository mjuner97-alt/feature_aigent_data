package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PendingClarification;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 单测：一对多歧义反问状态机（docs/dimension-alias-config-plan.md §6）。
 */
class DimensionStateManagerClarificationTest {

    private final DimensionStateManager manager =
            new DimensionStateManager(null, new AliasResolver(DimensionAliasSeed::builtin));

    private static final String AMBIGUOUS_QUESTION = "风险组7月份版本有多少需求项";

    private PendingClarification startAndCapture(String question) {
        String text = manager.startClarificationIfAmbiguous(question);
        assertNotNull(text, "歧义问题应返回反问文本");
        PendingClarification pending = manager.getCurrentState().getPendingClarification();
        assertNotNull(pending, "应登记 pendingClarification");
        return pending;
    }

    @Test
    void ambiguousQuestionRegistersPendingAndReturnsClarifyText() {
        String text = manager.startClarificationIfAmbiguous(AMBIGUOUS_QUESTION);
        PendingClarification pending = manager.getCurrentState().getPendingClarification();

        assertNotNull(text);
        assertTrue(text.contains("风险组"));
        assertTrue(text.contains("全球市场风险管理应用"));
        assertTrue(text.contains("金融产品定价与估值系统"));
        assertTrue(text.contains("1.") && text.contains("2."));
        assertTrue(text.contains("产品线"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, pending.getDimension());
        assertEquals("风险组", pending.getAlias());
        assertEquals(2, pending.getCandidates().size());
        assertEquals(AMBIGUOUS_QUESTION, pending.getOriginalQuestion());
    }

    @Test
    void unambiguousQuestionReturnsNullAndNoPending() {
        assertNull(manager.startClarificationIfAmbiguous("军队的需求项有多少"));
        assertNull(manager.getCurrentState() == null ? null : manager.getCurrentState().getPendingClarification());
    }

    @Test
    void nullResolverLegacyManagerNeverClarifies() {
        DimensionStateManager legacy = new DimensionStateManager(null);
        assertNull(legacy.startClarificationIfAmbiguous(AMBIGUOUS_QUESTION));
        assertNull(legacy.consumeClarification("1"));
    }

    @Test
    void consumeByIndexReplacesAliasWithStandardName() {
        PendingClarification pending = startAndCapture(AMBIGUOUS_QUESTION);
        String first = pending.getCandidates().get(0);

        String resolved = manager.consumeClarification("1");
        assertEquals(AMBIGUOUS_QUESTION.replace("风险组", first), resolved);
        assertEquals(PeerDimensionType.PRODUCT_LINE, manager.getCurrentState().getPeerDimension().getType());
        assertEquals(List.of(first), manager.getCurrentState().getPeerDimension().getValues());
        assertNull(manager.getCurrentState().getPendingClarification(), "命中后清除 pending");
    }

    @Test
    void consumeByChineseOrdinalAndPunctuatedIndex() {
        PendingClarification pending = startAndCapture(AMBIGUOUS_QUESTION);
        String second = pending.getCandidates().get(1);
        assertEquals(AMBIGUOUS_QUESTION.replace("风险组", second), manager.consumeClarification("第二个"));
        assertEquals(List.of(second), manager.getCurrentState().getPeerDimension().getValues());

        PendingClarification again = startAndCapture(AMBIGUOUS_QUESTION);
        String first = again.getCandidates().get(0);
        assertEquals(AMBIGUOUS_QUESTION.replace("风险组", first), manager.consumeClarification("1."));
    }

    @Test
    void consumeByTextContainingCandidate() {
        PendingClarification pending = startAndCapture(AMBIGUOUS_QUESTION);
        String second = pending.getCandidates().get(1);

        String resolved = manager.consumeClarification("我指的是" + second);
        assertEquals(AMBIGUOUS_QUESTION.replace("风险组", second), resolved);
        assertEquals(List.of(second), manager.getCurrentState().getPeerDimension().getValues());
    }

    @Test
    void missDropsPendingAndReturnsNull() {
        startAndCapture(AMBIGUOUS_QUESTION);

        assertNull(manager.consumeClarification("今天天气怎么样"));
        assertNull(manager.getCurrentState().getPendingClarification(), "未命中丢弃 pending");
        assertNull(manager.getCurrentState().getPeerDimension(), "未命中不写 peer 维度");
        // 丢弃后同一 pending 不可再次消费
        assertNull(manager.consumeClarification("1"));
    }

    @Test
    void outOfRangeIndexDropsPending() {
        startAndCapture(AMBIGUOUS_QUESTION);
        assertNull(manager.consumeClarification("3"));
        assertNull(manager.getCurrentState().getPendingClarification());
    }

    @Test
    void consumeWithoutPendingReturnsNull() {
        assertNull(manager.consumeClarification("1"));
    }

    @Test
    void clarifiedPeerReappliedToContextStateAndMapping() {
        // 确认后的标准名可能不在正则词表（"金融产品定价与估值系统"），维度上下文必须回填；
        // 只写维度+标准名（走 peerDimension 渲染），不进「同义词解析映射」段
        startAndCapture("风险组的缺陷密度9月份");
        String clarified = manager.consumeClarification("2");
        assertEquals("金融产品定价与估值系统的缺陷密度9月份", clarified);

        io.agentscope.core.agent.RuntimeContext ctx = io.agentscope.core.agent.RuntimeContext.builder()
                .sessionId("t1").userId("u").build();
        DimensionStateManager.ProcessResult result = manager.processQuestionInContext(ctx, clarified);
        assertEquals(PeerDimensionType.PRODUCT_LINE, result.newState().getPeerDimension().getType());
        assertEquals(List.of("金融产品定价与估值系统"),
                result.newState().getPeerDimension().getValues());
        assertTrue(result.resolvedAliases().isEmpty(), "确认轮不产出口语映射行");

        // 一次性回填：再次处理同一问题不再重复注入
        DimensionStateManager.ProcessResult second = manager.processQuestionInContext(ctx, clarified);
        assertNull(second.newState().getPeerDimension());
        assertTrue(second.resolvedAliases().isEmpty());
    }

    @Test
    void newPendingOverwritesOldOne() {
        startAndCapture(AMBIGUOUS_QUESTION);
        // 未消费 pending 前又来了一个新歧义问题 → 覆盖
        manager.startClarificationIfAmbiguous("三农政法的需求项有多少");
        PendingClarification pending = manager.getCurrentState().getPendingClarification();
        assertEquals("三农政法", pending.getAlias());
    }

    @Test
    void pendingExcludedFromCacheKey() {
        startAndCapture(AMBIGUOUS_QUESTION);
        DimensionState withPending = manager.getCurrentState();
        assertTrue(withPending.getPendingClarification() != null);
        // 反问轮没有维度内容 → 指纹为空（不进缓存），且 pending 不污染指纹
        assertEquals("", withPending.toCacheKey());
    }

    @Test
    void matchClarificationReplyOrdinalVariants() {
        List<String> candidates = List.of("A", "B", "C");
        assertEquals("A", DimensionStateManager.matchClarificationReply("1", candidates));
        assertEquals("A", DimensionStateManager.matchClarificationReply("１", candidates));
        assertEquals("A", DimensionStateManager.matchClarificationReply(" 1 ", candidates));
        assertEquals("B", DimensionStateManager.matchClarificationReply("第2个", candidates));
        assertEquals("B", DimensionStateManager.matchClarificationReply("第二个", candidates));
        assertEquals("C", DimensionStateManager.matchClarificationReply("3.", candidates));
        assertNull(DimensionStateManager.matchClarificationReply("0", candidates));
        assertNull(DimensionStateManager.matchClarificationReply("4", candidates));
        assertNull(DimensionStateManager.matchClarificationReply(null, candidates));
        assertNull(DimensionStateManager.matchClarificationReply("", candidates));
        // 非纯序号但包含候选名
        assertEquals("B", DimensionStateManager.matchClarificationReply("帮我查B的情况", candidates));
    }

    @Test
    void clarifyTextFormat() {
        String text = DimensionStateManager.buildClarifyText(
                "风险组", PeerDimensionType.PRODUCT_LINE, List.of("甲", "乙"));
        assertEquals("『风险组』在产品线维度有多个匹配，请确认具体指哪一个：\n1. 甲\n2. 乙\n（回复序号或名称即可）",
                text);
        assertFalse(DimensionStateManager.buildClarifyText(
                "X", PeerDimensionType.TEAM, List.of("甲")).isEmpty());
    }
}
