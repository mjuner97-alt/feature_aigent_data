package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.AliasResolver.AliasResolution;
import com.agentscopea2a.v2.dimension.AliasResolver.AmbiguousAlias;
import com.agentscopea2a.v2.dimension.AliasResolver.ResolvedAlias;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同义词解析五步算法（docs/dimension-alias-config-plan.md §4.2 / §8）。
 * 直接用内置种子数据驱动，不依赖数据库。
 */
class AliasResolverTest {

    private final AliasResolver resolver = new AliasResolver(DimensionAliasSeed::builtin);

    private AliasResolution resolve(String q) {
        return resolver.resolve(q);
    }

    private ResolvedAlias single(AliasResolution r) {
        assertEquals(0, r.ambiguous().size(), "不应有歧义: " + r.ambiguous());
        assertEquals(1, r.resolved().size(), "应恰好一个命中: " + r.resolved());
        return r.resolved().get(0);
    }

    // ── 军队：触发词完整词机制 ────────────────────────────────────────────

    @Test
    void bareArmyFallsToProductLineBecauseConditionalTeamRowNotTriggered() {
        ResolvedAlias hit = single(resolve("军队的需求项有多少"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("企业资金管理系统", hit.standardName());
    }

    @Test
    void armyGroupTriggersTeamRowAndBeatsProductLine() {
        ResolvedAlias hit = single(resolve("军队组的需求项有多少"));
        assertEquals(PeerDimensionType.TEAM, hit.dimension());
        assertEquals("特种业务组", hit.standardName());
    }

    // ── 一对多：同维度多候选 ⇒ ambiguous ────────────────────────────────

    @Test
    void riskGroupIsAmbiguousWithTwoProductLines() {
        AliasResolution r = resolve("风险组7月份版本有多少需求项");
        assertEquals(0, r.resolved().size());
        assertEquals(1, r.ambiguous().size());
        AmbiguousAlias amb = r.ambiguous().get(0);
        assertEquals(PeerDimensionType.PRODUCT_LINE, amb.dimension());
        assertEquals(2, amb.candidates().size());
        assertTrue(amb.candidates().contains("全球市场风险管理应用"));
        assertTrue(amb.candidates().contains("金融产品定价与估值系统"));
    }

    @Test
    void threeFarmPoliticsIsAmbiguousApplication() {
        AmbiguousAlias amb = resolve("三农政法的缺陷").ambiguous().get(0);
        assertEquals(PeerDimensionType.APPLICATION, amb.dimension());
        assertEquals(List.of("FS-LFS-FARM", "FS-GBCP-EPL"), amb.candidates());
    }

    // ── 无歧义优先（规则 c）─────────────────────────────────────────────

    @Test
    void treasuryPrefersUnambiguousProductLineOverAmbiguousApplication() {
        ResolvedAlias hit = single(resolve("国库相关的需求"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("代理国库", hit.standardName());
    }

    // ── 最长匹配优先（步骤 2）────────────────────────────────────────────

    @Test
    void longestMatchGbcSceneInnovationGroupBeatsGbc() {
        ResolvedAlias hit = single(resolve("GBC场景创新组这个版本怎么样"));
        assertEquals(PeerDimensionType.TEAM, hit.dimension());
        assertEquals("杭州服务支持部GBC场景创新组", hit.standardName());
    }

    @Test
    void longestMatchLoanConsumerGroupBeatsLoan() {
        ResolvedAlias hit = single(resolve("个贷消费组的需求项"));
        assertEquals(PeerDimensionType.TEAM, hit.dimension());
        assertEquals("个贷消费场景组", hit.standardName());
    }

    @Test
    void bareLoanFallsToProductLine() {
        ResolvedAlias hit = single(resolve("个贷的需求项有多少"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("个人信贷产品线", hit.standardName());
    }

    @Test
    void longestMatchQuickPayProductLine() {
        ResolvedAlias hit = single(resolve("快捷支付产品线的缺陷"));
        assertEquals("快捷支付系统", hit.standardName());
    }

    // ── 兜底序（规则 d）与幂等 ──────────────────────────────────────────

    @Test
    void bareGbcFallsBackToProductLineWhenBothDimsSingleCandidate() {
        // PRODUCT_LINE > TEAM 兜底序（业务 2026/09/21 拍板，方案 §10）
        ResolvedAlias hit = single(resolve("GBC的需求项"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("民生政务", hit.standardName());
    }

    @Test
    void bankEnterpriseTeamAliasNotConfusedWithGroupSuffix() {
        ResolvedAlias hit = single(resolve("银企团队的需求"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("银企产品线", hit.standardName());
    }

    @Test
    void standardNameInputIsIdempotent() {
        ResolvedAlias hit = single(resolve("杭州二部量化交易组的缺陷密度"));
        assertEquals(PeerDimensionType.TEAM, hit.dimension());
        assertEquals("杭州二部量化交易组", hit.standardName());
    }

    // ── 多命中与不命中 ──────────────────────────────────────────────────

    @Test
    void twoAliasesResolvedInQuestionOrder() {
        AliasResolution r = resolve("比较军队和量化组的需求项");
        assertEquals(0, r.ambiguous().size());
        assertEquals(2, r.resolved().size());
        assertEquals("企业资金管理系统", r.resolved().get(0).standardName());
        assertEquals("杭州二部量化交易组", r.resolved().get(1).standardName());
        assertTrue(r.resolved().get(0).start() < r.resolved().get(1).start());
    }

    @Test
    void noAliasHitReturnsEmpty() {
        AliasResolution r = resolve("今天天气怎么样");
        assertEquals(0, r.resolved().size());
        assertEquals(0, r.ambiguous().size());
    }

    @Test
    void nullOrBlankQuestionReturnsEmpty() {
        assertEquals(AliasResolution.EMPTY, resolve(null));
        assertEquals(AliasResolution.EMPTY, resolve("  "));
    }

    // ── 缓存快照刷新 ────────────────────────────────────────────────────

    @Test
    void refreshedSnapshotChangesResult() {
        // 快照按引用相等判断是否重建，变更须换新 list 实例
        java.util.concurrent.atomic.AtomicReference<List<DimensionAlias>> holder =
                new java.util.concurrent.atomic.AtomicReference<>(DimensionAliasSeed.builtin());
        AliasResolver dynamic = new AliasResolver(holder::get);
        // 初始："军队组" 触发词命中 → 落小组
        assertEquals("特种业务组", single(dynamic.resolve("军队组的需求项")).standardName());

        // 业务确认后给 TEAM/军队 去掉触发词 → "军队组"不再触发，裸"军队"双维度平 → 兜底序落产品线
        List<DimensionAlias> mutated = new java.util.ArrayList<>(holder.get());
        mutated.set(indexOfTeamArmy(mutated),
                new DimensionAlias(null, PeerDimensionType.TEAM, "军队", "特种业务组", null, true, null));
        holder.set(mutated);
        ResolvedAlias hit = single(dynamic.resolve("军队组的需求项"));
        assertEquals(PeerDimensionType.PRODUCT_LINE, hit.dimension());
        assertEquals("企业资金管理系统", hit.standardName());
    }

    @Test
    void disabledRowIsSkipped() {
        List<DimensionAlias> seed = new java.util.ArrayList<>(DimensionAliasSeed.builtin());
        seed.set(indexOfTeamArmy(seed),
                new DimensionAlias(null, PeerDimensionType.TEAM, "军队", "特种业务组", "军队组", false, null));
        AliasResolver dynamic = new AliasResolver(() -> seed);
        assertEquals(PeerDimensionType.PRODUCT_LINE, single(dynamic.resolve("军队组的需求项")).dimension());
    }

    private static int indexOfTeamArmy(List<DimensionAlias> seed) {
        for (int i = 0; i < seed.size(); i++) {
            DimensionAlias row = seed.get(i);
            if (row.dimension() == PeerDimensionType.TEAM && "军队".equals(row.alias())) {
                return i;
            }
        }
        throw new IllegalStateException("seed must contain TEAM/军队");
    }

    @Test
    void seedTriggerKeywordOnArmyRow() {
        DimensionAlias army = DimensionAliasSeed.builtin().stream()
                .filter(r -> r.dimension() == PeerDimensionType.TEAM && "军队".equals(r.alias()))
                .findFirst().orElseThrow();
        assertEquals("军队组", army.triggerKeyword());
        assertNull(army.remark());
    }
}
