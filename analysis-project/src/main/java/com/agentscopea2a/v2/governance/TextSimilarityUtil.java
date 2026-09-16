/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.governance;

/**
 * 治理检测共用的文本相似度算法: cosine / 归一化 Levenshtein / bigram Jaccard / 名称归一化。
 * 全部无状态纯函数, 不依赖分词器 (中文描述用字符 bigram 逼近词级 Jaccard)。
 */
public final class TextSimilarityUtil {

    private TextSimilarityUtil() {}

    /** 余弦相似度; 任一向量 null / 长度不等 / 零模时返回 0 (视为无信号)。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 归一化 Levenshtein 相似度 ∈ [0,1]; 空串对空串视为 1。 */
    public static double levenshteinSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        return 1.0 - ((double) levenshteinDistance(a, b)) / max;
    }

    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /** 字符 bigram Jaccard ∈ [0,1]; 长度 < 2 时退化为精确相等 (1/0)。 */
    public static double bigramJaccard(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        java.util.Set<String> ga = bigrams(a);
        java.util.Set<String> gb = bigrams(b);
        int intersection = 0;
        for (String g : ga) {
            if (gb.contains(g)) intersection++;
        }
        int union = ga.size() + gb.size() - intersection;
        return union == 0 ? 0 : ((double) intersection) / union;
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /**
     * 名称归一化: 全角->半角、小写、去空白, 统一分隔符 '_' '-' '.' 为 '_'。
     * 用于 skill name / keywords / toolId 之间的可比对形态。
     */
    public static String normalizeName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {
                c = ' ';
            }
            if (c == '-' || c == '.' || c == ' ' || c == '/') {
                sb.append('_');
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim();
    }
}
