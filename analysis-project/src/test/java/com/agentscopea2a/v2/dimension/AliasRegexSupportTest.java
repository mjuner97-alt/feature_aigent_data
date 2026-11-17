package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 动态维度键正则捕获词表: 模板识别 / 提取替换 / 保存校验 (dimension-key 底稿 §6.4)。 */
class AliasRegexSupportTest {

    @Test
    void regexTemplateDetection() {
        assertTrue(AliasRegexSupport.isRegexTemplate("$1"));
        assertTrue(AliasRegexSupport.isRegexTemplate("大于$1"));
        assertTrue(AliasRegexSupport.isRegexTemplate("$2公里"));
        assertFalse(AliasRegexSupport.isRegexTemplate("Q1"));
        assertFalse(AliasRegexSupport.isRegexTemplate("价格$元")); // $ 后无数字非模板
        assertFalse(AliasRegexSupport.isRegexTemplate(null));
    }

    @Test
    void maxCaptureGroupParsesAllReferences() {
        assertEquals(1, AliasRegexSupport.maxCaptureGroup("$1"));
        assertEquals(3, AliasRegexSupport.maxCaptureGroup("a$1b$3"));
        assertEquals(0, AliasRegexSupport.maxCaptureGroup("普通值"));
    }

    @Test
    void extractSubstitutesCaptureGroups() {
        List<String> values = AliasRegexSupport.extract(
                "杭州三部 11月份版本未闭环问题数，跨部门流转次数>3",
                "流转次数\\s*>\\s*(\\d+(?:\\.\\d+)?)", "$1");
        assertEquals(List.of("3"), values);
    }

    @Test
    void extractCollectsMultipleMatchesDedupedKeepOrder() {
        List<String> values = AliasRegexSupport.extract(
                "流转次数>3 和 流转次数>5 以及 流转次数>3",
                "流转次数>\\s*(\\d+)", "$1");
        assertEquals(List.of("3", "5"), values);
    }

    @Test
    void extractSupportsTemplateTextAroundReference() {
        // 模板可带前后缀: ">3" → "大于3" (脚本侧按枚举值过滤)
        List<String> values = AliasRegexSupport.extract(
                "流转次数>3 的缺陷", "流转次数>\\s*(\\d+)", "大于$1");
        assertEquals(List.of("大于3"), values);
    }

    @Test
    void extractSkipsMatchWhenReferencedGroupIsNull() {
        // (x)? 可选组未参与匹配 → 该次命中跳过, 不产出 null/空串
        List<String> values = AliasRegexSupport.extract(
                "流转次数超3次", "流转次数(超)?>?(\\d+)?", "$2");
        assertEquals(List.of("3"), values);
    }

    @Test
    void extractReturnsEmptyOnInvalidRegexOrMissingGroups() {
        assertTrue(AliasRegexSupport.extract("q", "([bad", "$1").isEmpty());
        assertTrue(AliasRegexSupport.extract("q", "q", "$1").isEmpty()); // 引用组越界
    }

    @Test
    void extractTerminatesOnZeroWidthMatch() {
        // (?:x)* 可零宽命中 → 终止不空转
        assertEquals(List.of(), AliasRegexSupport.extract("abc", "(?:z)*", "$0"));
    }

    @Test
    void validateRegexRowAcceptsValidAndRejectsBroken() {
        assertNull(AliasRegexSupport.validateRegexRow("流转次数\\s*>\\s*(\\d+)", "$1"));
        assertTrue(AliasRegexSupport.validateRegexRow("([bad", "$1") != null);
        String msg = AliasRegexSupport.validateRegexRow("q", "$2"); // 组越界
        assertTrue(msg != null && msg.contains("$2"));
        String badRegex = AliasRegexSupport.validateRegexRow("[", "$1");
        assertTrue(badRegex.contains("不是合法正则"));
    }

    @Test
    void yearTokenUsesCapturedYearWhenPresent() {
        // 2026-11-17 投产日期动态维度: {year:$n} 补年令牌, 组命中取捕获值
        assertTrue(AliasRegexSupport.isRegexTemplate("{year:$1}-$2-$3"));
        assertEquals(3, AliasRegexSupport.maxCaptureGroup("{year:$1}-$2-$3"));
        List<String> withYear = AliasRegexSupport.extract(
                "2026年10月26日投产的情况如何",
                "(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]",
                "{year:$1}-$2-$3");
        assertEquals(List.of("2026-10-26"), withYear);
    }

    @Test
    void yearTokenDefaultsToCurrentYearWhenGroupAbsent() {
        // 年前缀可省: 组为空 → 当前年 (Asia/Shanghai, 同 TimeRangeResolver.ZONE);
        // 令牌使年前缀可选 (组 null 不再令整次命中无效, 与裸 $n 语义不同)
        String currentYear = String.valueOf(java.time.LocalDate.now(TimeRangeResolver.ZONE).getYear());
        List<String> values = AliasRegexSupport.extract(
                "10月26日投产的情况如何",
                "(?:(\\d{4}|\\d{2})年)?(\\d{1,2})月(\\d{1,2})[日号]",
                "{year:$1}-$2-$3");
        assertEquals(List.of(currentYear + "-10-26"), values);
    }

    @Test
    void yearTokenExpandsTwoDigitYear() {
        // 2位年补 20 前缀 (与 DimensionStateManager.yearOf 同口径)
        List<String> values = AliasRegexSupport.extract(
                "26年10月26日投产的情况如何",
                "(?:(\\d{4}|\\d{2})年)?(\\d{1,2})月(\\d{1,2})[日号]",
                "{year:$1}-$2-$3");
        assertEquals(List.of("2026-10-26"), values);
    }
}
