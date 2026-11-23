package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Applies material-type page ignore rules to an import preview. */
final class PptMaterialIgnoreRule {
    private static final Pattern CHAPTER = Pattern.compile("^\\s*(?:第\\s*)?([0-9]+|[一二三四五六七八九十百零]+)(?:章)?(?:[.．、\\-－\\s]|$)");

    private PptMaterialIgnoreRule() {}

    static List<PptAnomalyMapper.PageAssignment> apply(List<PptAnomalyMapper.PageAssignment> pages,
                                                         List<PptAnomalyMapper.MaterialIgnoreRule> rules) {
        if (rules == null || rules.isEmpty()) return pages;
        var result = new ArrayList<PptAnomalyMapper.PageAssignment>(pages.size());
        boolean skipSection = false;
        for (var page : pages) {
            Integer chapter = chapterNumber(page.title());
            if (chapter != null) skipSection = false;
            boolean sectionWasActive = skipSection;
            boolean ignored = rules.stream().anyMatch(rule -> matchesPage(rule, page.title(), chapter, sectionWasActive));
            if (ignored) skipSection = skipSection || rules.stream().anyMatch(rule -> "CHAPTER".equals(rule.ruleType())
                    && "SECTION".equals(rule.ruleScope()) && matchesChapter(rule.ruleValue(), chapter));
            result.add(ignored ? new PptAnomalyMapper.PageAssignment(page.page(), page.title(), page.section(), true,
                    "NO_SCAN", page.excluded()) : page);
        }
        return result;
    }

    private static boolean matchesPage(PptAnomalyMapper.MaterialIgnoreRule rule, String title, Integer chapter,
                                       boolean sectionActive) {
        if (!rule.enabled()) return false;
        if ("KEYWORD".equals(rule.ruleType())) return "PAGE".equals(rule.ruleScope())
                && title != null && title.contains(rule.ruleValue());
        if (!"CHAPTER".equals(rule.ruleType())) return false;
        return ("SECTION".equals(rule.ruleScope()) && (sectionActive || matchesChapter(rule.ruleValue(), chapter)))
                || ("PAGE".equals(rule.ruleScope()) && matchesChapter(rule.ruleValue(), chapter));
    }

    private static boolean matchesChapter(String value, Integer chapter) {
        try { return chapter != null && Integer.parseInt(value) == chapter; }
        catch (NumberFormatException e) { return false; }
    }

    static Integer chapterNumber(String title) {
        if (title == null) return null;
        var match = CHAPTER.matcher(title);
        if (!match.find()) return null;
        String value = match.group(1);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return chineseNumber(value); }
    }

    private static Integer chineseNumber(String value) {
        int total = 0, current = 0;
        for (char c : value.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(c);
            if (digit >= 0) current = digit;
            else if (c == '十') { total += current == 0 ? 10 : current * 10; current = 0; }
            else if (c == '百') { total += (current == 0 ? 1 : current) * 100; current = 0; }
        }
        return total + current;
    }
}
