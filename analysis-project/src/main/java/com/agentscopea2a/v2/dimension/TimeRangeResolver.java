package com.agentscopea2a.v2.dimension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 时间范围锚点解析（docs/202610/dimension-key-dynamic-dimension-plan.md §6.1、
 * docs/202610/time-dimension-plan.md §2/§4）。
 *
 * <p>提取层产出语义锚点（不是字面量），本类在执行期解析为 [start, end] 闭区间
 * （ISO yyyy-MM-dd，含端点）。解析结果绝不进指纹/缓存——"本周"昨天问和今天问结果不同，
 * 日期必须在执行时计算（同"当前在建版本"缺省在执行期注入的先例）。
 *
 * <p>锚点白名单（配置可写的）分两型：
 * <ul>
 *   <li>日期计算型：THIS_WEEK / LAST_WEEK / LAST:{n}d / LAST:{n}w / NEXT:{n}w /
 *       TODAY / THIS_MONTH / THIS_YEAR——执行期算 start/end；</li>
 *   <li>里程碑透传型：交付周至投产周 / 投产周 / T-{n} / R-{n}——锚点值即脚本收到的
 *       字面量，仅裸键 timeRange 透传，resolve 返回 null（不解析日期）；</li>
 *   <li>歧义型：AMBIGUOUS:WEEKS:{n} / AMBIGUOUS:PROD——不进执行，触发反问。</li>
 * </ul>
 * DATE:a~b / UNTIL:date 仅由 TimeRangeExtractor 正则产出，禁止人工配置；
 * LAST:{n}V / LAST:{n}Q 归版本季度方案（VersionPlanAnchorResolver），此处不解析。
 *
 * <p>口径拍板（time-dimension-plan.md §7②③，2026-10-27 终版）：下周=未来1周（NEXT:1w，
 * today~today+7）；历史区间**含今天**（起点不变，仅终点由 today-1 放宽为 today）——
 * 本周/近一周（历史）=today-7~today、近N天=today-N~today、近N周=today-7N~today；
 * 上周=日历周（上周一~上周日），不随本周滚动。
 */
public final class TimeRangeResolver {

    /** 保留键：dimension_key_config 中唯一允许走时间范围提取语义的 key。 */
    public static final String RESERVED_KEY = "timeRange";

    /** 后缀形态：param_mapping 绑定脚本 start/end 参数。 */
    public static final String KEY_START = RESERVED_KEY + ".start";
    public static final String KEY_END = RESERVED_KEY + ".end";

    /** 时区拍板：显式 Asia/Shanghai，不随服务器 locale 漂移。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 里程碑透传锚点（锚点值即脚本收到的字面量，time-dimension-plan.md §2.2）。 */
    public static final String DELIV_TO_PROD = "交付周至投产周";
    public static final String PROD_WEEK = "投产周";

    /** 配置期锚点白名单（DATE:/UNTIL: 与暂缓的 LAST:{n}V/Q 不在其中）。 */
    private static final Pattern CONFIG_ANCHOR_PATTERN =
            Pattern.compile("THIS_WEEK|LAST_WEEK|LAST:[1-9][0-9]*d|LAST:[1-9][0-9]*w"
                    + "|NEXT:[1-9][0-9]*w|TODAY|THIS_MONTH|THIS_YEAR"
                    + "|AMBIGUOUS:WEEKS:[1-9][0-9]*|AMBIGUOUS:PROD"
                    + "|" + DELIV_TO_PROD + "|" + PROD_WEEK
                    + "|T-[1-9][0-9]*|R-[1-9][0-9]*");

    private TimeRangeResolver() {
    }

    /** 保存期校验：dimension_key_config(key=timeRange) 的 aliases.value 必须匹配白名单。 */
    public static boolean isValidConfigAnchor(String value) {
        return value != null && CONFIG_ANCHOR_PATTERN.matcher(value).matches();
    }

    /**
     * 键全集展开：keys 含 timeRange 时追加 .start/.end 后缀形态，
     * 供 activeKeys() 与 param_mapping 保存校验共用（单一来源）。
     */
    public static LinkedHashSet<String> expandKeys(Set<String> keys) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(keys);
        if (expanded.contains(RESERVED_KEY)) {
            expanded.add(KEY_START);
            expanded.add(KEY_END);
        }
        return expanded;
    }

    /**
     * 解析锚点为 [start, end]（ISO，含端点）；不可解析（透传型/歧义型/未实现形态）返回 null。
     *
     * <p>口径（2026-10-27 终版，历史含今天、起点不变）：本周=today-7~today；
     * 上周=日历周（上周一~上周日）；近 n 天=today-n~today；近 n 周=today-7n~today；
     * 未来 n 周=today~today+7n（含今天）。
     */
    public static String[] resolve(String anchor, LocalDate today) {
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        String a = anchor.trim();
        return switch (a) {
            case "TODAY" -> new String[]{iso(today), iso(today)};
            case "THIS_WEEK" -> new String[]{iso(today.minusDays(7)), iso(today)};
            case "LAST_WEEK" -> lastCalendarWeek(today);
            case "THIS_MONTH" -> new String[]{iso(today.withDayOfMonth(1)), iso(today)};
            case "THIS_YEAR" -> new String[]{iso(today.withDayOfYear(1)), iso(today)};
            default -> resolveParametric(a, today);
        };
    }

    /** 参数化锚点（LAST:{n}d/w、NEXT:{n}w、UNTIL:{date}）；透传/歧义/其他形态 → null。 */
    private static String[] resolveParametric(String anchor, LocalDate today) {
        if (anchor.startsWith("LAST:") || anchor.startsWith("NEXT:")) {
            boolean next = anchor.startsWith("NEXT:");
            String tail = anchor.substring(anchor.indexOf(':') + 1);
            try {
                if (tail.endsWith("d")) {
                    int n = Integer.parseInt(tail.substring(0, tail.length() - 1));
                    if (!next && n >= 1) {
                        return new String[]{iso(today.minusDays(n)), iso(today)};
                    }
                } else if (tail.endsWith("w")) {
                    int n = Integer.parseInt(tail.substring(0, tail.length() - 1));
                    if (n >= 1) {
                        return next
                                ? new String[]{iso(today), iso(today.plusWeeks(n))}
                                : new String[]{iso(today.minusWeeks(n)), iso(today)};
                    }
                }
            } catch (NumberFormatException ignored) {
                // 落到 return null
            }
            return null;
        }
        if (anchor.startsWith("UNTIL:")) {
            try {
                LocalDate end = LocalDate.parse(anchor.substring("UNTIL:".length()).trim(), ISO);
                return new String[]{iso(end.withDayOfYear(1)), iso(end)};
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** DATE:a~b 锚点解析（仅提取层产出）；非法区间返回 null。 */
    public static String[] resolveDateRange(String anchor) {
        if (anchor == null || !anchor.startsWith("DATE:")) {
            return null;
        }
        String[] parts = anchor.substring("DATE:".length()).split("~", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalDate start = LocalDate.parse(parts[0].trim(), ISO);
            LocalDate end = LocalDate.parse(parts[1].trim(), ISO);
            if (start.isAfter(end)) {
                return null;
            }
            return new String[]{iso(start), iso(end)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ==================== 歧义反问（time-dimension-plan.md §3.3） ====================

    /** 歧义锚点判定：AMBIGUOUS:WEEKS:{n}（近N周方向）/ AMBIGUOUS:PROD（投产前二选一）。 */
    public static boolean isAmbiguousAnchor(String anchor) {
        return anchor != null && (anchor.equals("AMBIGUOUS:PROD")
                || anchor.startsWith("AMBIGUOUS:WEEKS:"));
    }

    /** 歧义锚点的反问文案（DirectSkillExecutor 短路下发）。 */
    public static String ambiguityAskPrompt(String anchor) {
        if (anchor != null && anchor.startsWith("AMBIGUOUS:WEEKS:")) {
            return "您说的「近" + anchor.substring("AMBIGUOUS:WEEKS:".length())
                    + "周」是指历史近N周（往前推），还是未来近N周？请回复：历史 / 未来";
        }
        if ("AMBIGUOUS:PROD".equals(anchor)) {
            return "您说的「投产前」是指投产周，还是交付周至投产周？请回复：投产周 / 交付周至投产周";
        }
        return null;
    }

    /**
     * 用户对歧义反问的答复 → 确定性锚点；答非所问返回 null（调用方丢弃 pending 回退正常链路）。
     * 方向词含"交付"优先判（"交付周至投产周"同时含"投产"）。
     */
    public static String resolveAmbiguousReply(String reply, String anchor) {
        if (reply == null || reply.isBlank() || anchor == null) {
            return null;
        }
        String r = reply.trim();
        if (anchor.startsWith("AMBIGUOUS:WEEKS:")) {
            String n = anchor.substring("AMBIGUOUS:WEEKS:".length());
            if (r.contains("未来") || r.contains("往后") || r.contains("接下来") || r.contains("今后")
                    || r.contains("以后")) {
                return "NEXT:" + n + "w";
            }
            if (r.contains("历史") || r.contains("往前") || r.contains("之前") || r.contains("过去")
                    || r.contains("前面") || r.contains("先前")) {
                return "LAST:" + n + "w";
            }
            return null;
        }
        if ("AMBIGUOUS:PROD".equals(anchor)) {
            if (r.contains("交付")) {
                return DELIV_TO_PROD;
            }
            if (r.contains("投产")) {
                return PROD_WEEK;
            }
            return null;
        }
        return null;
    }

    private static String iso(LocalDate d) {
        return d.format(ISO);
    }

    /** 上周=日历周（拍板⑦ 2026-10-27）：上周一 ~ 上周日（本周一整体前移一周）。 */
    private static String[] lastCalendarWeek(LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7);
        return new String[]{iso(monday), iso(monday.plusDays(6))};
    }
}
