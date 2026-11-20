package com.agentscopea2a.v2.pptanomaly;

import java.util.List;
import java.util.Locale;

/** 标题下的关键词扫描规则；关键词按行保存且必须全部命中。 */
public record PptScanRule(String title, String name, String keywords, String mode,
                          Double upperLimitRatio, Integer firstRow, Integer lastRow) {
    public List<String> terms() {
        return keywords.lines().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public boolean matches(String text) {
        String normalized = normalize(text);
        return !terms().isEmpty() && terms().stream().allMatch(term -> normalized.contains(normalize(term)));
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\p{javaWhitespace}\\p{Zs}]+", "").toLowerCase(Locale.ROOT);
    }

    static List<PptScanRule> forTitle(List<PptScanRule> rules, String title, boolean exact) {
        String key = exact ? title : PptAnomalyService.unnumberedTitle(title);
        return rules.stream().filter(rule -> key.equals(exact ? rule.title()
                : PptAnomalyService.unnumberedTitle(rule.title()))).toList();
    }
}
