package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** "我X" 口语解析 + 人名字典扫描（人员台账维度方案 §3/§4.1）：
 *  正则命中 / MYSELF 抑制与触发 / 降级契约 / 最长优先 span 消费。 */
class PersonalDimensionResolverTest {

    private final PersonLedgerRepository ledger = mock(PersonLedgerRepository.class);
    private final PersonalDimensionResolver resolver = new PersonalDimensionResolver(ledger);

    private PersonLedgerRepository.PersonLedgerRow row(String dept, String group, String app,
                                                       String productLine, String name) {
        return new PersonLedgerRepository.PersonLedgerRow(
                dept == null ? List.of() : List.of(dept),
                group == null ? List.of() : List.of(group),
                app == null ? List.of() : List.of(app),
                productLine == null ? List.of() : List.of(productLine),
                name, "555153289");
    }

    @Test
    void myDeptResolvesLedgerAffiliation() {
        when(ledger.byUserId("555153289")).thenReturn(row("杭州开发三部", null, null, null, "徐三金"));

        PersonalDimensionResolver.PersonalResolution res =
                resolver.resolve("我部10月的缺陷数", "555153289");

        assertTrue(res.myDept());
        assertFalse(res.myGroup());
        assertFalse(res.myself());
        assertTrue(res.anyHit());
        assertEquals(List.of("杭州开发三部"), res.ledger().depts());
    }

    @Test
    void compoundColloquialWordsAllHit() {
        when(ledger.byUserId("555153289")).thenReturn(
                row("杭州开发三部", "数据智能组", "F-demo", "质量产品线", "徐三金"));

        PersonalDimensionResolver.PersonalResolution res =
                resolver.resolve("我的组和我的应用、我的产品线、本部门", "555153289");

        assertTrue(res.myGroup());
        assertTrue(res.myApp());
        assertTrue(res.myProductLine());
        assertTrue(res.myDept());
        // 具体维度全命中 → MYSELF 抑制（§3.6）
        assertFalse(res.myself());
    }

    @Test
    void myselfSuppressedWhenSpecificDimensionHit() {
        when(ledger.byUserId("555153289")).thenReturn(row("杭州开发三部", null, null, null, "徐三金"));

        // MYSELF 只在无任何具体维度命中时触发（§3.6）; 台账查询不受影响（归属照常解析）
        assertFalse(resolver.resolve("我部达标率", "555153289").myself());
        assertFalse(resolver.resolve("我们组的缺陷", "555153289").myself());
        assertFalse(resolver.resolve("我的应用群组发布情况", "555153289").myself());
    }

    @Test
    void bareMyselfTriggersLedgerQuery() {
        // 裸"我"（后随非中文/非组们的字符，前非中文）：不含"我部/我们"等复合词
        when(ledger.byUserId("555153289")).thenReturn(row(null, null, null, null, "徐三金"));

        PersonalDimensionResolver.PersonalResolution res =
                resolver.resolve("对比一下，我 10月的缺陷数", "555153289");

        assertTrue(res.myself());
        assertEquals("徐三金", res.ledger().name());
    }

    @Test
    void noHitSkipsLedgerQuery() {
        PersonalDimensionResolver.PersonalResolution res =
                resolver.resolve("杭州开发三部的缺陷数", "555153289");

        assertFalse(res.anyHit());
        assertNull(res.ledger());
        verifyNoInteractions(ledger);
    }

    @Test
    void blankUserIdKeepsFlagsButNoLedger() {
        // 匿名降级（§3.5）：命中标志保留，不查台账
        PersonalDimensionResolver.PersonalResolution res = resolver.resolve("我部达标率", null);

        assertTrue(res.myDept());
        assertNull(res.ledger());
        verifyNoInteractions(ledger);
    }

    @Test
    void unknownUserDegradesToNullLedger() {
        when(ledger.byUserId("555153289")).thenReturn(null);

        PersonalDimensionResolver.PersonalResolution res =
                resolver.resolve("我部达标率", "555153289");

        assertTrue(res.myDept());
        assertNull(res.ledger());
    }

    @Test
    void blankQuestionReturnsNone() {
        assertFalse(resolver.resolve(null, "555153289").anyHit());
        assertFalse(resolver.resolve("  ", "555153289").anyHit());
    }

    // ==================== §4.1 人名字典扫描 ====================

    private void stubDictionary(String... names) {
        when(ledger.nameDictionary()).thenReturn(Set.of(names));
    }

    @Test
    void scanNamesFindsDictionaryHits() {
        stubDictionary("徐三金", "杨三君");

        assertEquals(List.of("徐三金", "杨三君"),
                resolver.scanNames("对比徐三金和杨三君的缺陷数"));
    }

    @Test
    void scanNamesConsumesLongestMatchFirst() {
        // 最长优先 + span 消费：张三丰 整段吃掉, 剩余 和张三 里再中 张三
        stubDictionary("张三丰", "张三");

        assertEquals(List.of("张三丰", "张三"), resolver.scanNames("张三丰和张三"));
    }

    @Test
    void scanNamesSkipsShorterWhenLongerHits() {
        // 徐三金 命中后窗口越过, 不会再用 三金 之类重复收
        stubDictionary("徐三金", "三金");

        assertEquals(List.of("徐三金"), resolver.scanNames("徐三金的缺陷"));
    }

    @Test
    void scanNamesNoDictionaryHitReturnsEmpty() {
        stubDictionary("徐三金");

        assertTrue(resolver.scanNames("缺陷数最多的部门").isEmpty());
    }

    @Test
    void scanNamesDisabledWhenDictionaryEmpty() {
        when(ledger.nameDictionary()).thenReturn(Set.of());

        assertTrue(resolver.scanNames("徐三金的缺陷").isEmpty());
    }

    @Test
    void scanNamesBlankInputReturnsEmpty() {
        stubDictionary("徐三金");

        assertTrue(resolver.scanNames(null).isEmpty());
        assertTrue(resolver.scanNames("  ").isEmpty());
    }

    @Test
    void resolveNameDelegatesToLedger() {
        when(ledger.byName("徐三金")).thenReturn(List.of(
                new PersonLedgerRepository.PersonIdentity("徐三金", "555153289",
                        List.of("杭州开发三部"), List.of())));

        assertEquals(1, resolver.resolveName("徐三金").size());
        assertEquals("555153289", resolver.resolveName("徐三金").get(0).perNo());
    }

    // ==================== 我X 正则动态配置 (我X方案 §8) + 角色双路径提取 (岗位角色方案 §3.1b/§3.1d) ====================

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<DimensionKeyConfigRepository>
    keyProvider(DimensionKeyConfigRepository repo) {
        org.springframework.beans.factory.ObjectProvider<DimensionKeyConfigRepository> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repo);
        return provider;
    }

    private static DimensionKeyConfig.AliasMapping regex(String alias, String value) {
        return new DimensionKeyConfig.AliasMapping(alias, value, DimensionKeyConfig.AliasMapping.TYPE_REGEX);
    }

    @Test
    void configuredRegexOverlaysHardcodedBaseline() {
        // 配置条目任一命中即成立 (§8.2 叠加判定): "我担任" 是 MYSELF 硬编码盲区, 配置补上
        DimensionKeyConfigRepository repo = mock(DimensionKeyConfigRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(new DimensionKeyConfig(
                2L, "perNo", "统一认证号", true, List.of(regex("我担任|我现任|我所担任", "myself")), true, null)));
        PersonalDimensionResolver configured =
                new PersonalDimensionResolver(ledger, keyProvider(repo));
        when(ledger.byUserId("555153289")).thenReturn(row(null, null, null, null, "徐三金"));

        PersonalDimensionResolver.PersonalResolution res =
                configured.resolve("我担任开发组长的达标率", "555153289");

        assertTrue(res.myself());
        // 硬编码基线照常生效 (叠加, 非替换)
        assertTrue(configured.resolve("我部达标率", "555153289").myDept());
    }

    @Test
    void configuredMyDeptRegexHitsAndSuppressesMyself() {
        DimensionKeyConfigRepository repo = mock(DimensionKeyConfigRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(new DimensionKeyConfig(
                1L, "deptName", "部门", true, List.of(regex("我们科室|本组", "myDept")), true, null)));
        PersonalDimensionResolver configured =
                new PersonalDimensionResolver(ledger, keyProvider(repo));
        when(ledger.byUserId("555153289")).thenReturn(row("杭州三部", null, null, null, "徐三金"));

        PersonalDimensionResolver.PersonalResolution res =
                configured.resolve("我们科室的达标率", "555153289");

        assertTrue(res.myDept());
        assertFalse(res.myself()); // 具体维度命中 → MYSELF 抑制 (§3.6)
    }

    @Test
    void invalidConfiguredRegexSkippedWithoutBreakingBaseline() {
        DimensionKeyConfigRepository repo = mock(DimensionKeyConfigRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(new DimensionKeyConfig(
                2L, "perNo", "统一认证号", true,
                List.of(regex("[invalid(", "myself"), regex("我担任", "myself")), true, null)));
        PersonalDimensionResolver configured =
                new PersonalDimensionResolver(ledger, keyProvider(repo));
        when(ledger.byUserId("555153289")).thenReturn(row(null, null, null, null, "徐三金"));

        // 非法 regex 跳过 (其余照用), 合法条目仍命中
        assertTrue(configured.resolve("我担任组长的达标率", "555153289").myself());
        assertFalse(configured.resolve("普通问题", "555153289").anyHit());
    }

    @Test
    void configRepoFailureFallsBackToHardcoded() {
        DimensionKeyConfigRepository repo = mock(DimensionKeyConfigRepository.class);
        when(repo.findAllEnabled()).thenThrow(new RuntimeException("db down"));
        PersonalDimensionResolver configured =
                new PersonalDimensionResolver(ledger, keyProvider(repo));

        assertTrue(configured.resolve("我部达标率", "555153289").myDept());
    }

    @Test
    void configuredExtractRegexReplacesDefault() {
        // 配置了 extract 正则 → 硬编码 担任... 兜底不再生效 (§8.2 替换语义, 只对该标签)
        DimensionKeyConfigRepository repo = mock(DimensionKeyConfigRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(new DimensionKeyConfig(
                11L, "perRole", "岗位角色", true,
                List.of(regex("出任([一-龥]{2,10}?)(?=[的，。？?！!、]|$)", "extract")), true, null)));
        PersonalDimensionResolver configured =
                new PersonalDimensionResolver(ledger, keyProvider(repo));
        when(ledger.roleDictionary()).thenReturn(Set.of("测试经理"));

        assertEquals(List.of("测试经理"), configured.extractRolesByPattern("我出任测试经理的达标率"));
        assertTrue(configured.extractRolesByPattern("我担任测试经理的达标率").isEmpty());
    }

    @Test
    void extractRolesByPatternNormalizesAgainstRoleDictionary() {
        // 捕获值 → 字典包含关系归一 (双向包含, 最长字典值优先); 字典无命中原样收
        when(ledger.roleDictionary()).thenReturn(Set.of("测试经理", "高级测试经理"));

        // 捕获"高级测试经理" 字典直中
        assertEquals(List.of("高级测试经理"),
                resolver.extractRolesByPattern("担任高级测试经理的达标率"));
        // 捕获"测试" → 字典两个值都包含它 → 收最长的
        assertEquals(List.of("高级测试经理"),
                resolver.extractRolesByPattern("担任测试的达标率"));
        // 字典无命中 → 原样收 (fail-open)
        assertEquals(List.of("架构师"),
                resolver.extractRolesByPattern("担任架构师的达标率"));
    }

    @Test
    void extractRolesByDictionaryLongestFirstAndSpanMask() {
        when(ledger.roleDictionary()).thenReturn(Set.of("项目经理", "高级项目经理", "经理"));

        // 高级项目经理 最长优先吃掉 span, 不会再命中 经理; 两处命中按问句位置排序
        assertEquals(List.of("高级项目经理", "项目经理"),
                resolver.extractRolesByDictionary("高级项目经理和项目经理的达标率", true));
        assertEquals(List.of("高级项目经理"),
                resolver.extractRolesByDictionary("高级项目经理的达标率", true));
    }

    @Test
    void extractRolesByDictionaryRequiresPersonAnchor() {
        when(ledger.roleDictionary()).thenReturn(Set.of("项目经理"));

        // 人锚闸门 (§3.1d): 无 myself/人名命中 → 不收 (按角色全量查询边界不破)
        assertTrue(resolver.extractRolesByDictionary("项目经理的达标率", false).isEmpty());
        assertTrue(resolver.extractRolesByDictionary(null, true).isEmpty());
        // 字典不可用 → fail-safe 空
        when(ledger.roleDictionary()).thenReturn(Set.of());
        assertTrue(resolver.extractRolesByDictionary("张三项目经理的达标率", true).isEmpty());
    }
}
