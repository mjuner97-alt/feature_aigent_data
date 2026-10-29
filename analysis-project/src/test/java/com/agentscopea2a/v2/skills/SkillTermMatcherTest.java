package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** matchPositions 位置原语（检索关键词与维度值冲突治理方案.md §4）：regex / literal / 零宽安全。 */
class SkillTermMatcherTest {

    @Test
    void literalTermPositions() {
        // normalize: lowercase + trim
        String q = SkillTermMatcher.normalize("杭州四部风险组和风险资产业务组");
        List<int[]> pos = SkillTermMatcher.matchPositions(q, "风险");
        assertEquals(2, pos.size());
        assertEquals(4, pos.get(0)[0]);
        assertEquals(6, pos.get(0)[1]);
        assertEquals(8, pos.get(1)[0]);
        assertEquals(10, pos.get(1)[1]);
    }

    @Test
    void regexTermPositions() {
        String q = SkillTermMatcher.normalize("近30天的达标率");
        List<int[]> pos = SkillTermMatcher.matchPositions(q, "近\\d+天");
        assertEquals(1, pos.size());
        assertEquals(0, pos.get(0)[0]);
        assertEquals(q.indexOf("天") + 1, pos.get(0)[1]);
    }

    @Test
    void invalidRegexFallsBackToLiteral() {
        // 含非法正则语法的词条按字面量匹配
        List<int[]> pos = SkillTermMatcher.matchPositions("abc[风险]xyz", "风险]");
        assertEquals(1, pos.size());
        assertEquals(4, pos.get(0)[0]);
    }

    @Test
    void noMatchReturnsEmpty() {
        assertTrue(SkillTermMatcher.matchPositions("达标率", "缺陷").isEmpty());
    }

    @Test
    void nullOrBlankInputsReturnEmpty() {
        assertTrue(SkillTermMatcher.matchPositions(null, "风险").isEmpty());
        assertTrue(SkillTermMatcher.matchPositions("风险", null).isEmpty());
        assertTrue(SkillTermMatcher.matchPositions("风险", "  ").isEmpty());
    }

    @Test
    void zeroWidthMatchDoesNotLoopForever() {
        // 零宽正则 (如 a*) 若不推进 region 会死循环
        List<int[]> pos = SkillTermMatcher.matchPositions("abc", "a*");
        assertEquals(4, pos.size(), "abc 三字符 + 末尾各一个零宽锚点");
    }
}
