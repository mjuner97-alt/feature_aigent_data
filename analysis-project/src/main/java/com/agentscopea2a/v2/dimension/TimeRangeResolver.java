package com.agentscopea2a.v2.dimension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 时间范围锚点解析（docs/202610/dimension-key-dynamic-dimension-plan.md §6.1）。
 *
 * <p>提取层产出语义锚点（不是字面量），本类在执行期解析为 [start, end] 闭区间
 * （ISO yyyy-MM-dd，含端点）。解析结果绝不进指纹/缓存——"本周"昨天问和今天问结果不同，
 * 日期必须在执行时计算（同"当前在建版本"缺省在执行期注入的先例）。
 *
 * <p>锚点白名单（配置可写的）：THIS_WEEK / LAST_WEEK / LAST:{n}d / THIS_MONTH / THIS_YEAR；
 * DATE:a~b 仅由 TimeRangeExtractor 的绝对区间正则产出，禁止人工配置；
 * LAST:{n}V / LAST:{n}Q 暂缓（版本/季度→日期口径未定），保存期即拒绝。
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

    /** 配置期锚点白名单（DATE: 与暂缓的 LAST:{n}V/Q 不在其中）。 */
    private static final Pattern CONFIG_ANCHOR_PATTERN =
            Pattern.compile("THIS_WEEK|LAST_WEEK|LAST:[1-9][0-9]*d|THIS_MONTH|THIS_YEAR");

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
     * 解析锚点为 [start, end]（ISO，含端点）；不可解析（未实现的锚点形态）返回 null。
     *
     * <p>拍板（2026-10-14）：本周 = 本周一 ~ 今天；近 n 天 = today-(n-1) ~ today（含今天）。
     */
    public static String[] resolve(String anchor, LocalDate today) {
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        String a = anchor.trim();
        return switch (a) {
            case "THIS_WEEK" -> new String[]{
                    iso(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))), iso(today)};
            case "LAST_WEEK" -> {
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new String[]{iso(thisMonday.minusWeeks(1)), iso(thisMonday.minusDays(1))};
            }
            case "THIS_MONTH" -> new String[]{iso(today.withDayOfMonth(1)), iso(today)};
            case "THIS_YEAR" -> new String[]{iso(today.withDayOfYear(1)), iso(today)};
            default -> resolveParametric(a, today);
        };
    }

    /** LAST:{n}d 参数化锚点；其他（LAST:{n}V/Q、DATE: 混入配置等）本期不解析 → null。 */
    private static String[] resolveParametric(String anchor, LocalDate today) {
        if (!anchor.startsWith("LAST:")) {
            return null;
        }
        String tail = anchor.substring("LAST:".length());
        if (tail.endsWith("d")) {
            try {
                int n = Integer.parseInt(tail.substring(0, tail.length() - 1));
                if (n >= 1) {
                    return new String[]{iso(today.minusDays(n - 1L)), iso(today)};
                }
            } catch (NumberFormatException ignored) {
                // 落到 return null
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

    private static String iso(LocalDate d) {
        return d.format(ISO);
    }
}
