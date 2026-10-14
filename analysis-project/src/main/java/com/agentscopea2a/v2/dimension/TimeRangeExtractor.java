package com.agentscopea2a.v2.dimension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 时间范围提取（docs/history/dimension-key-dynamic-dimension-plan.md §6.1.3）。
 *
 * <p>两层确定性提取，产出语义锚点列表（去重、按问题中出现顺序）：
 * <ul>
 *   <li>L1 绝对区间正则（代码内置）：「10月1日到10月15日」「2026-10-01~10-15」等，
 *       缺年补当前年 → {@code DATE:yyyy-MM-dd~yyyy-MM-dd}；非法日期（13月32日）整段跳过；</li>
 *   <li>L2 词表 contains（timeRange 行的 aliases，配置可扩）：锚点值来自配置；
 *       键内按 alias 长度降序 accept、重叠丢弃（「前一周」压更短的词）。</li>
 * </ul>
 *
 * <p>L1 先占 span（更具体），L2 只在剩余文本上命中；两层结果按位置排序合并。
 */
public final class TimeRangeExtractor {

    /** 中文日期区间：[可选年]M月D日 (到|至|~|～|—|–|-) [可选年]M月D日。 */
    private static final java.util.regex.Pattern CN_RANGE = java.util.regex.Pattern.compile(
            "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(?:到|至|~|～|—|–|-)\\s*"
                    + "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    /** 数字日期区间：yyyy.M.d (分隔符) [yyyy.]M.d，点/横线/斜杠分隔。 */
    private static final java.util.regex.Pattern NUM_RANGE = java.util.regex.Pattern.compile(
            "(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})\\s*(?:到|至|~|～|—|–|-)\\s*"
                    + "(?:(\\d{4})[./-])?(\\d{1,2})[./-](\\d{1,2})");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private TimeRangeExtractor() {
    }

    /**
     * @param question 当前问题原文
     * @param aliases  timeRange 行的词表（alias → 锚点值）
     * @return 锚点列表（按出现顺序去重）；无命中返回空列表
     */
    public static List<String> extract(String question,
                                       List<DimensionKeyConfig.AliasMapping> aliases) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        collectRegexSpans(question, CN_RANGE, spans);
        collectRegexSpans(question, NUM_RANGE, spans);

        if (aliases != null) {
            List<DimensionKeyConfig.AliasMapping> sorted = aliases.stream()
                    .filter(a -> a != null && a.valid())
                    .sorted(Comparator.comparingInt((DimensionKeyConfig.AliasMapping a) -> a.alias().length()).reversed())
                    .toList();
            for (DimensionKeyConfig.AliasMapping alias : sorted) {
                int from = 0;
                while (true) {
                    int at = question.indexOf(alias.alias(), from);
                    if (at < 0) {
                        break;
                    }
                    int end = at + alias.alias().length();
                    if (spans.stream().noneMatch(s -> at < s.end && end > s.start)) {
                        spans.add(new Span(at, end, alias.value()));
                    }
                    from = at + 1;
                }
            }
        }
        return spans.stream()
                .sorted(Comparator.comparingInt(s -> s.start))
                .map(s -> s.anchor)
                .distinct()
                .toList();
    }

    private static void collectRegexSpans(String question, java.util.regex.Pattern pattern,
                                          List<Span> out) {
        java.util.regex.Matcher m = pattern.matcher(question);
        while (m.find()) {
            String anchor = normalizeRegexMatch(m);
            if (anchor != null) {
                out.add(new Span(m.start(), m.end(), anchor));
            }
        }
    }

    /** 正则命中 → DATE: 锚点；日期非法（13月等）返回 null 整段跳过。缺年补当前年。
     *  两种正则分组布局一致：1=起始年(可选) 2=起始月 3=起始日 4=结束年(可选) 5=结束月 6=结束日。 */
    private static String normalizeRegexMatch(java.util.regex.Matcher m) {
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        Integer y1 = groupOrNull(m, 1);
        Integer mo1 = groupOrNull(m, 2);
        Integer d1 = groupOrNull(m, 3);
        Integer y2 = groupOrNull(m, 4);
        Integer mo2 = groupOrNull(m, 5);
        Integer d2 = groupOrNull(m, 6);
        if (mo1 == null || d1 == null || mo2 == null || d2 == null) {
            return null;
        }
        int year1 = y1 != null ? y1 : today.getYear();
        int year2 = y2 != null ? y2 : year1;
        try {
            LocalDate start = LocalDate.of(year1, mo1, d1);
            LocalDate end = LocalDate.of(year2, mo2, d2);
            if (start.isAfter(end)) {
                return null;
            }
            return "DATE:" + start.format(ISO) + "~" + end.format(ISO);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static Integer groupOrNull(java.util.regex.Matcher m, int group) {
        String v = m.group(group);
        return v == null ? null : Integer.valueOf(v);
    }

    private record Span(int start, int end, String anchor) {
    }
}
