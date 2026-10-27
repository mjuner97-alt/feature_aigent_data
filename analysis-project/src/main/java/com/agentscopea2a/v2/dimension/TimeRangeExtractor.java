package com.agentscopea2a.v2.dimension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间范围提取（docs/202610/dimension-key-dynamic-dimension-plan.md §6.1.3、
 * docs/202610/time-dimension-plan.md §3）。
 *
 * <p>L1 正则（代码内置，按优先级先占 span，后面的正则与 L2 词表只认剩余文本）：
 * <ol>
 *   <li>中文/数字绝对区间 → {@code DATE:a~b}（缺年补当前年）</li>
 *   <li>截至X日 → {@code UNTIL:date}（缺年补当前年、缺月补当前月，拍板④）</li>
 *   <li>同月省略区间「10月1日到15日」/ 双省月「5日到10日」→ {@code DATE:a~b}</li>
 *   <li>临近交付N周内 → {@code R-n}、T-X → {@code T-n}（里程碑透传锚点，不解析日期）</li>
 *   <li>未来/今后/下N周 → {@code NEXT:nw}；近N周 → {@code AMBIGUOUS:WEEKS:n}（歧义反问，拍板⑤）</li>
 *   <li>近N天 → {@code LAST:nd}（历史不含今天口径；中文数字/阿拉伯数字均命中，2026-10-27 补）</li>
 * </ol>
 * L2 词表 contains（timeRange 行的 aliases，配置可扩）：锚点值来自配置；
 * 键内按 alias 长度降序 accept、重叠丢弃（「前一周」压更短的词）。
 */
public final class TimeRangeExtractor {

    /** 中文日期区间：[可选年]M月D日 (到|至|~|～|—|–|-) [可选年]M月D日。 */
    private static final Pattern CN_RANGE = Pattern.compile(
            "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(?:到|至|~|～|—|–|-)\\s*"
                    + "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    /** 数字日期区间：yyyy.M.d (分隔符) [yyyy.]M.d，点/横线/斜杠分隔。 */
    private static final Pattern NUM_RANGE = Pattern.compile(
            "(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})\\s*(?:到|至|~|～|—|–|-)\\s*"
                    + "(?:(\\d{4})[./-])?(\\d{1,2})[./-](\\d{1,2})");

    /** 截至X日：(截止|截至) [年][M月]D日；缺年补当前年、缺月补当前月（时间维度方案拍板④）。 */
    private static final Pattern UNTIL = Pattern.compile(
            "(?:截止|截至)\\s*(?:(\\d{4})\\s*年\\s*)?(?:(\\d{1,2})\\s*月\\s*)?(\\d{1,2})\\s*[日号]");

    /** 同月省略区间：[年]M月D日 (到|至|…) D日（第二段无"月"，补起始月）。 */
    private static final Pattern CN_RANGE_TAIL = Pattern.compile(
            "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(?:到|至|~|～|—|–|-)\\s*"
                    + "(\\d{1,2})\\s*[日号]");

    /** 双省月区间：D日 (到|至|…) D日 → 本月（时间维度方案 §3.1③）。 */
    private static final Pattern DAY_RANGE = Pattern.compile(
            "(\\d{1,2})\\s*日\\s*(?:到|至|~|～|—|–|-)\\s*(\\d{1,2})\\s*[日号]");

    /** 周数词：阿拉伯数字或中文数字（含"两"），1-99。 */
    private static final String CN_NUM = "[一两二三四五六七八九十0-9]{1,3}";

    /** 临近交付N周内 → R-{n}（透传锚点；中文数字"两周"也命中，业务例句原话）。 */
    private static final Pattern NEAR_DELIVERY = Pattern.compile("临近交付\\s*(" + CN_NUM + ")\\s*周内?");

    /** T-X / R-X → T-{n} / R-{n}（透传锚点，周后缀可省）。 */
    private static final Pattern MILESTONE = Pattern.compile("([TR])[-—]\\s*([1-9][0-9]*)\\s*周?");

    /** 未来/今后/下 N周 → NEXT:{n}w（含今天，today ~ today+7n）。 */
    private static final Pattern NEXT_WEEKS = Pattern.compile("(?:未来|今后|下)\\s*(" + CN_NUM + ")\\s*周");

    /** 近 N周 → AMBIGUOUS:WEEKS:{n}（方向歧义，执行链路反问，不做假设；含"近一周"，拍板按业务表追问逻辑）。 */
    private static final Pattern LAST_WEEKS = Pattern.compile("近\\s*(" + CN_NUM + ")\\s*周");

    /** 近 N天 → LAST:{n}d（2026-10-27 新增：词表只配了 近7天/近30天 封闭词，近58天 类漏识；
     *  历史口径 today-N~today 含今天，"近15日" 因量词"日"≠"天"不误命中）。 */
    private static final Pattern LAST_DAYS = Pattern.compile("近\\s*(" + CN_NUM + ")\\s*天");

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
        List<Span> spans = collectL1Spans(question, false);

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

    /**
     * L1 日期类 span（{start, end} 数组，口语值方案 §3.2）：
     * 供内置键词表合并屏蔽——日期区间里的月份数字不得被版本词表误收（"10月1日到10月15日"
     * 里的 "1月"/"10月"）。含全部日期类正则（区间/截至/省月形态），不含透传与周类。
     */
    public static List<int[]> absoluteDateSpans(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        return collectL1Spans(question, true).stream()
                .filter(s -> s.anchor.startsWith("DATE:") || s.anchor.startsWith("UNTIL:"))
                .map(s -> new int[] {s.start(), s.end()})
                .toList();
    }

    /**
     * 缓存兜底判定（ResponseCacheMiddleware，字典不可用时）：问题命中随"今天"漂移的
     * 相对时间正则（未来N周/近N周/近N天）→ true。透传锚点（T-/R-/临近交付）确定性字面量，不在此列。
     */
    public static boolean matchesDriftRegex(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return NEXT_WEEKS.matcher(question).find() || LAST_WEEKS.matcher(question).find()
                || LAST_DAYS.matcher(question).find();
    }

    private static List<Span> collectL1Spans(String question, boolean datesOnly) {
        List<Span> spans = new ArrayList<>();
        addRegexSpans(question, CN_RANGE, TimeRangeExtractor::rangeAnchor, spans);
        addRegexSpans(question, NUM_RANGE, TimeRangeExtractor::rangeAnchor, spans);
        addRegexSpans(question, UNTIL, TimeRangeExtractor::untilAnchor, spans);
        addRegexSpans(question, CN_RANGE_TAIL, TimeRangeExtractor::tailRangeAnchor, spans);
        addRegexSpans(question, DAY_RANGE, TimeRangeExtractor::dayRangeAnchor, spans);
        if (!datesOnly) {
            addRegexSpans(question, NEAR_DELIVERY,
                    m -> weeks(m.group(1), 1) == null ? null : "R-" + weeks(m.group(1), 1), spans);
            addRegexSpans(question, MILESTONE, m -> m.group(1) + "-" + m.group(2), spans);
            addRegexSpans(question, NEXT_WEEKS,
                    m -> weeks(m.group(1), 1) == null ? null : "NEXT:" + weeks(m.group(1), 1) + "w", spans);
            addRegexSpans(question, LAST_WEEKS,
                    m -> weeks(m.group(1), 1) == null ? null : "AMBIGUOUS:WEEKS:" + weeks(m.group(1), 1), spans);
            addRegexSpans(question, LAST_DAYS,
                    m -> weeks(m.group(1), 1) == null ? null : "LAST:" + weeks(m.group(1), 1) + "d", spans);
        }
        return spans;
    }

    /** 周数（中文/阿拉伯）解析，n < min 视为无效返回 null（整段跳过）。 */
    private static Integer weeks(String raw, int min) {
        Integer n = cnNumber(raw);
        return n != null && n >= min ? n : null;
    }

    /** 中文/阿拉伯数字解析（1-99，含"两"）；非法返回 null。 */
    private static Integer cnNumber(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        if (s.matches("\\d{1,2}")) {
            return Integer.valueOf(s);
        }
        String normalized = s.replace('两', '二');
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int val = 10;
            if (ten > 0) {
                int tens = digitOf(normalized.charAt(ten - 1));
                if (tens <= 0) {
                    return null;
                }
                val = tens * 10;
            }
            if (ten < normalized.length() - 1) {
                int ones = digitOf(normalized.charAt(ten + 1));
                if (ones <= 0) {
                    return null;
                }
                val += ones;
            }
            return val >= 1 ? val : null;
        }
        return normalized.length() == 1 && digitOf(normalized.charAt(0)) > 0
                ? digitOf(normalized.charAt(0)) : null;
    }

    private static int digitOf(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    /** 按优先级追加 span：与已有 span 重叠（被更长/更早的正则占走）的命中丢弃。 */
    private static void addRegexSpans(String question, Pattern pattern,
                                      java.util.function.Function<Matcher, String> anchorFn,
                                      List<Span> out) {
        Matcher m = pattern.matcher(question);
        while (m.find()) {
            String anchor = anchorFn.apply(m);
            if (anchor == null) {
                continue;
            }
            int start = m.start();
            int end = m.end();
            if (out.stream().noneMatch(s -> start < s.end && end > s.start)) {
                out.add(new Span(start, end, anchor));
            }
        }
    }

    /** CN_RANGE / NUM_RANGE 共用分组布局：1=起始年(可选) 2=起始月 3=起始日 4=结束年(可选) 5=结束月 6=结束日。 */
    private static String rangeAnchor(Matcher m) {
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

    /** UNTIL 分组：1=年(可选) 2=月(可选) 3=日；缺年补当前年、缺月补当前月；非法日期整段跳过。 */
    private static String untilAnchor(Matcher m) {
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        Integer y = groupOrNull(m, 1);
        Integer mo = groupOrNull(m, 2);
        Integer d = groupOrNull(m, 3);
        if (d == null) {
            return null;
        }
        try {
            LocalDate end = LocalDate.of(y != null ? y : today.getYear(),
                    mo != null ? mo : today.getMonthValue(), d);
            return "UNTIL:" + end.format(ISO);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** 同月省略区间分组：1=年(可选) 2=月 3=起始日 4=结束日；结束段补起始月。 */
    private static String tailRangeAnchor(Matcher m) {
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        Integer y = groupOrNull(m, 1);
        Integer mo = groupOrNull(m, 2);
        Integer d1 = groupOrNull(m, 3);
        Integer d2 = groupOrNull(m, 4);
        if (mo == null || d1 == null || d2 == null) {
            return null;
        }
        int year = y != null ? y : today.getYear();
        try {
            LocalDate start = LocalDate.of(year, mo, d1);
            LocalDate end = LocalDate.of(year, mo, d2);
            if (start.isAfter(end)) {
                return null;
            }
            return "DATE:" + start.format(ISO) + "~" + end.format(ISO);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** 双省月区间分组：1=起始日 2=结束日；年月均补当前。 */
    private static String dayRangeAnchor(Matcher m) {
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        Integer d1 = groupOrNull(m, 1);
        Integer d2 = groupOrNull(m, 2);
        if (d1 == null || d2 == null) {
            return null;
        }
        try {
            LocalDate start = LocalDate.of(today.getYear(), today.getMonthValue(), d1);
            LocalDate end = LocalDate.of(today.getYear(), today.getMonthValue(), d2);
            if (start.isAfter(end)) {
                return null;
            }
            return "DATE:" + start.format(ISO) + "~" + end.format(ISO);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static Integer groupOrNull(Matcher m, int group) {
        String v = m.group(group);
        return v == null ? null : Integer.valueOf(v);
    }

    private record Span(int start, int end, String anchor) {
    }
}
