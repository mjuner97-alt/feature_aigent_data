package com.agentscopea2a.v2.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSimilarityUtilTest {

    @Test
    void cosineIdenticalVectors() {
        float[] v = {0.1f, 0.2f, 0.3f};
        assertEquals(1.0, TextSimilarityUtil.cosine(v, v), 1e-9);
    }

    @Test
    void cosineOrthogonalVectors() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0, TextSimilarityUtil.cosine(a, b), 1e-9);
    }

    @Test
    void cosineNullOrMismatchedReturnsZero() {
        assertEquals(0.0, TextSimilarityUtil.cosine(null, new float[] {1f}), 1e-9);
        assertEquals(0.0, TextSimilarityUtil.cosine(new float[] {1f}, new float[] {1f, 2f}), 1e-9);
        assertEquals(0.0, TextSimilarityUtil.cosine(new float[] {0f}, new float[] {0f}), 1e-9);
    }

    @Test
    void levenshteinSimilaritySameAndTotallyDifferent() {
        assertEquals(1.0, TextSimilarityUtil.levenshteinSimilarity("abc", "abc"), 1e-9);
        assertEquals(1.0, TextSimilarityUtil.levenshteinSimilarity("", ""), 1e-9);
        assertEquals(0.0, TextSimilarityUtil.levenshteinSimilarity("abc", "xyz"), 1e-9);
        // edit distance 1 / max length 4
        assertEquals(0.75, TextSimilarityUtil.levenshteinSimilarity("abcd", "abce"), 1e-9);
    }

    @Test
    void bigramJaccardChineseNearDup() {
        double same = TextSimilarityUtil.bigramJaccard("按部门查询质量指标", "按部门查询质量指标");
        assertEquals(1.0, same, 1e-9);
        double related = TextSimilarityUtil.bigramJaccard("按部门查询质量指标", "按部门统计质量指标");
        double unrelated = TextSimilarityUtil.bigramJaccard("按部门查询质量指标", "生成项目周报");
        assertTrue(related > unrelated, "related=" + related + " unrelated=" + unrelated);
    }

    @Test
    void bigramJaccardShortStringsFallBackToEquality() {
        assertEquals(1.0, TextSimilarityUtil.bigramJaccard("a", "a"));
        assertEquals(0.0, TextSimilarityUtil.bigramJaccard("a", "b"));
    }

    @Test
    void normalizeNameUnifiesWidthCaseAndSeparators() {
        assertEquals(
                TextSimilarityUtil.normalizeName("Q2-1.ByDept"),
                TextSimilarityUtil.normalizeName("q2_1_bydept"));
        assertEquals(
                TextSimilarityUtil.normalizeName("ｑ２＿１"),
                TextSimilarityUtil.normalizeName("q2_1"));
        assertEquals("", TextSimilarityUtil.normalizeName(null));
    }
}
