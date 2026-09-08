package com.agentscopea2a.v2.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则提取：部门简称/别名必须归一化到 KNOWLEDGE.md 标准名，否则同指纹积累失效。 */
class DimensionStateManagerRuleTest {

    private final DimensionStateManager manager = new DimensionStateManager(null);

    private List<String> extractDepartments(String question) {
        return manager.analyzeQuestionRuleBased(question).getExplicitDimensions().getDepartments();
    }

    private DimensionState.TimeDimension extractTime(String question) {
        return manager.analyzeQuestionRuleBased(question).getExplicitDimensions().getTimeDimension();
    }

    private int currentYear() {
        return java.time.LocalDate.now().getYear();
    }

    @Test
    void standardFullNameStillMatches() {
        assertEquals(List.of("杭州开发五部"), extractDepartments("杭州开发五部4月版的质量"));
        assertEquals(List.of("云计算实验室"), extractDepartments("云计算实验室这个季度怎么样"));
    }

    @Test
    void shortFormsNormalizeToStandardNames() {
        assertEquals(List.of("杭州开发五部"), extractDepartments("杭州五部4月版的质量"));
        assertEquals(List.of("杭州开发一部"), extractDepartments("一部4月份版本质量如何"));
        assertEquals(List.of("杭州开发二部"), extractDepartments("看下二部的缺陷密度"));
        assertEquals(List.of("杭州技术部"), extractDepartments("技术部4月版质量"));
        assertEquals(List.of("杭州服务支持部"), extractDepartments("服务支持部这个版本怎么样"));
        assertEquals(List.of("杭州服务支持部"), extractDepartments("服务支持的缺陷情况"));
        assertEquals(List.of("云计算实验室"), extractDepartments("云计算4月版质量"));
        assertEquals(List.of("云计算实验室"), extractDepartments("实验室的质量怎么样"));
    }

    @Test
    void multipleDepartmentsInOneQuestion() {
        List<String> depts = extractDepartments("一部和二部这个版本哪个质量好");
        assertEquals(2, depts.size());
        assertTrue(depts.contains("杭州开发一部"));
        assertTrue(depts.contains("杭州开发二部"));
    }

    @Test
    void sameDepartmentDifferentSpellingsProduceSameResult() {
        assertEquals(extractDepartments("杭州开发五部4月版的质量"), extractDepartments("杭州五部4月版的质量"));
    }

    @Test
    void multipleVersionsCollected() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("一部和二部4月版和5月版的质量对比");
        assertEquals(DimensionState.TimeDimensionType.VERSION, time.getType());
        assertEquals(List.of(y + "年4月份版本", y + "年5月份版本"), time.getValues());
    }

    @Test
    void multipleFullVersionsCollected() {
        DimensionState.TimeDimension time = extractTime("2026年4月份版本和2026年5月份版本哪个好");
        assertEquals(
                List.of("2026年4月份版本", "2026年5月份版本"), time.getValues());
    }

    @Test
    void multipleQuartersCollectedWithAliases() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("Q1和二季度的缺陷情况");
        assertEquals(DimensionState.TimeDimensionType.QUARTER, time.getType());
        assertEquals(List.of(y + "年1季度", y + "年2季度"), time.getValues());
    }

    @Test
    void quarterShortAndFullDedupe() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime(y + "年1季度和Q1质量");
        assertEquals(List.of(y + "年1季度"), time.getValues());
    }

    @Test
    void yearPrefixOverridesCurrentYear() {
        DimensionState.TimeDimension time = extractTime("2025年4月的质量怎么样");
        assertEquals(List.of("2025年4月份版本"), time.getValues());
    }

    @Test
    void versionWithoutYearPrefixFallsBackToCurrentYear() {
        String y = String.valueOf(currentYear());
        DimensionState.TimeDimension time = extractTime("4月份版本的质量");
        assertEquals(List.of(y + "年4月份版本"), time.getValues());
    }
}
