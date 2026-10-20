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
package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 版本/季度锚点解析（docs/202610/版本季度部门口语值处理方案.md §3.1/§3.3）。
 *
 * <p>提取层产出语义锚点（THIS_V / THIS_Q / YEAR_V / HALF_V:{1|2} / LAST:{n}V / DELAYED），
 * 本类在执行期经火车日历（{@link VersionCalendarRepository}）与在建版本查询
 * （{@link VersionPlanKnowledgeHook}）展开为版本计划值列表 + 聚合时间窗口。
 * 解析结果绝不进指纹/缓存（同 timeRange 拍板②语义）。
 *
 * <p>拍板（2026-10-19）：季度是独立维度，季度值不展开为月度版本——THIS_Q 只解析出季度值；
 * "X月延后版"是标准值，仅裸词"延后版"（DELAYED）按当前在建月份定位。
 * 日历不可用/版本查无 → 解析为空 → 必填缺失回退 LLM（fail-safe）。
 */
public class VersionPlanAnchorResolver {

    private static final Logger log = LoggerFactory.getLogger(VersionPlanAnchorResolver.class);

    /**
     * 锚点白名单（dimension_key_config 词表 value 可写形态；标准值校验另见 isValidConfigValue）。
     * YEAR_V/HALF_V 允许 @yyyy 年份限定形态（"25年上半年" → HALF_V:1@2025，提取层产出）。
     */
    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "THIS_V|THIS_Q|YEAR_V(?:@\\d{4})?|HALF_V:[12](?:@\\d{4})?|LAST:[1-9][0-9]*V|DELAYED");

    /** 部门标准枚举（deptName 词表 value 校验，与 normalizeDepartment 目标一致）。 */
    private static final Set<String> DEPT_STANDARD_NAMES = Set.of(
            "杭州开发一部", "杭州开发二部", "杭州开发三部", "杭州开发四部", "杭州开发五部",
            "杭州服务支持部", "杭州技术部", "云计算实验室");

    private static final Pattern VERSION_STANDARD =
            Pattern.compile("\\d{4}年\\d{1,2}月份版本");
    private static final Pattern DELAYED_STANDARD =
            Pattern.compile("\\d{4}年\\d{1,2}月延后版");
    private static final Pattern QUARTER_STANDARD =
            Pattern.compile("\\d{4}年[1-4]季度");
    private static final Pattern QUARTER_NUM = Pattern.compile("(\\d{4})年([1-4])季度");
    private static final Pattern ANCHOR_PHRASE_REGEX =
            Pattern.compile("(?:近|最近)\\d{1,3}个(?:版本|月)|\\d{1,2}月份?延后");

    private final VersionCalendarRepository calendar;
    /** 在建版本查询（VersionPlanKnowledgeHook::currentVersions，TTL 缓存；须自行容错）。 */
    private final Supplier<List<String>> currentVersionsSupplier;

    /** 解析产物：展开后的版本值列表（裸键传参）+ 聚合时间窗口（.start/.end 传参，可为 null）。 */
    public record VersionResolution(List<String> versions, String start, String end) {}

    public VersionPlanAnchorResolver(
            VersionCalendarRepository calendar, Supplier<List<String>> currentVersionsSupplier) {
        this.calendar = calendar;
        this.currentVersionsSupplier = currentVersionsSupplier;
    }

    /** value 是否是语义锚点（锚点原词绝不透传给脚本）。 */
    public static boolean isAnchor(String value) {
        return value != null && ANCHOR_PATTERN.matcher(value.trim()).matches();
    }

    /**
     * 保存期校验（DimensionKeyController，口语值方案 §3.6）：
     * versionPlan = 版本标准值 / 延后版标准值 / 版本锚点；vestQuarter = 季度标准值 / THIS_Q；
     * deptName = 部门标准枚举。其他键不校验（返回 true）。
     */
    public static boolean isValidConfigValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        return switch (key == null ? "" : key) {
            case "versionPlan" -> VERSION_STANDARD.matcher(v).matches()
                    || DELAYED_STANDARD.matcher(v).matches()
                    || (isAnchor(v) && !"THIS_Q".equals(v));
            case "vestQuarter" -> QUARTER_STANDARD.matcher(v).matches() || "THIS_Q".equals(v);
            case "deptName" -> DEPT_STANDARD_NAMES.contains(v);
            default -> true;
        };
    }

    /**
     * 键全集展开：keys 含 versionPlan/vestQuarter 时追加 .start/.end 后缀形态，
     * 供 activeKeys() 与 param_mapping 保存校验共用（须在 TimeRangeResolver.expandKeys 之后串）。
     */
    public static LinkedHashSet<String> expandKeys(Set<String> keys) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(keys);
        if (expanded.contains("versionPlan")) {
            expanded.add("versionPlan.start");
            expanded.add("versionPlan.end");
        }
        if (expanded.contains("vestQuarter")) {
            expanded.add("vestQuarter.start");
            expanded.add("vestQuarter.end");
        }
        return expanded;
    }

    /**
     * 缓存绕过判定（ResponseCacheMiddleware，§3.5）：问题命中锚点短语（词表 alias 原词或
     * 数字形态正则）即不读不写——锚点解析结果随"今天"与日历漂移。标准值命中不绕过。
     */
    public static boolean containsAnchorPhrase(String question, List<DimensionKeyConfig> rows) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (ANCHOR_PHRASE_REGEX.matcher(question).find()) {
            return true;
        }
        if (rows == null) {
            return false;
        }
        for (DimensionKeyConfig row : rows) {
            if (!"versionPlan".equals(row.key()) && !"vestQuarter".equals(row.key())) {
                continue;
            }
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (alias.valid() && isAnchor(alias.value()) && question.contains(alias.alias())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 裸键展开：锚点 → 日历/在建版本展开的版本值列表；标准值原样透传；
     * 无法解析的锚点丢弃（不入传参）。任一值展开失败不影响其他值。
     */
    public List<String> expandVersions(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (!isAnchor(value)) {
                if (!out.contains(value)) {
                    out.add(value);
                }
                continue;
            }
            VersionResolution r = resolveAnchor(value.trim(), LocalDate.now(TimeRangeResolver.ZONE));
            if (r != null) {
                r.versions().forEach(v -> {
                    if (!out.contains(v)) {
                        out.add(v);
                    }
                });
            }
        }
        return List.copyOf(out);
    }

    /**
     * 首个可解析值的解析体（.start/.end 后缀键取第一个，多锚点边界同 timeRange §6.1.6）：
     * 锚点 → 展开；版本标准值 → 日历单版本窗口；季度标准值 → 该季度月度版本聚合窗口。
     * 不可解析（锚点展开为空/版本不在日历/日历不可用）→ null。
     */
    public VersionResolution resolveFirst(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now(TimeRangeResolver.ZONE);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String v = value.trim();
            if (isAnchor(v)) {
                VersionResolution r = resolveAnchor(v, today);
                if (r != null) {
                    return r;
                }
                continue;
            }
            if (QUARTER_STANDARD.matcher(v).matches()) {
                List<String> months = quarterToMonthVersions(v);
                VersionCalendarRepository.VersionWindow w = calendar.aggregateWindow(months);
                if (w != null) {
                    return new VersionResolution(months, w.start(), w.end());
                }
                continue;
            }
            VersionCalendarRepository.VersionWindow w = calendar.aggregateWindow(List.of(v));
            if (w != null) {
                return new VersionResolution(List.of(v), w.start(), w.end());
            }
        }
        return null;
    }

    /** 单锚点展开；展开结果为空（日历不可用/查无）返回 null。 */
    private VersionResolution resolveAnchor(String anchor, LocalDate today) {
        // @yyyy 年份限定（提取层"25年上半年"→HALF_V:1@2025）：剥后缀，展开用限定年
        Integer qualifierYear = null;
        int at = anchor.indexOf('@');
        if (at >= 0) {
            qualifierYear = Integer.parseInt(anchor.substring(at + 1));
            anchor = anchor.substring(0, at);
        }
        List<String> paramVersions;   // 裸键传参值（THIS_Q 传季度值本身）
        List<String> windowVersions;  // 时间窗口口径（季度按季度内月度版本聚合，§2.4）
        try {
            switch (anchor) {
                case "THIS_V" -> {
                    paramVersions = safeCurrentVersions();
                    windowVersions = paramVersions;
                }
                case "THIS_Q" -> {
                    List<String> quarters = VersionPlanKnowledgeHook.deriveQuarters(safeCurrentVersions());
                    paramVersions = quarters;
                    windowVersions = quarters.stream()
                            .flatMap(q -> quarterToMonthVersions(q).stream())
                            .toList();
                }
                case "YEAR_V" -> {
                    paramVersions = monthVersions(anchorYear(qualifierYear, today), 1, 12);
                    windowVersions = paramVersions;
                }
                case "HALF_V:1" -> {
                    paramVersions = monthVersions(anchorYear(qualifierYear, today), 1, 6);
                    windowVersions = paramVersions;
                }
                case "HALF_V:2" -> {
                    paramVersions = monthVersions(anchorYear(qualifierYear, today), 7, 12);
                    windowVersions = paramVersions;
                }
                case "DELAYED" -> {
                    String target = today.getYear() + "年" + today.getMonthValue() + "月延后版";
                    paramVersions = calendar.containsVersion(target) ? List.of(target) : List.of();
                    windowVersions = paramVersions;
                }
                default -> {
                    if (!anchor.startsWith("LAST:")) {
                        return null;
                    }
                    // 口径（拍板①终版）：在建版本的最后一个版本往前推 x 个；
                    // 在建锚点缺失（查询失败/日历查无）退化为日历最新版本
                    paramVersions = calendar.lastVersions(parseLastN(anchor), lastInBuildAnchor());
                    windowVersions = paramVersions;
                }
            }
        } catch (RuntimeException e) {
            log.warn("VersionPlanAnchorResolver: 锚点 {} 解析失败: {}", anchor, e.getMessage());
            return null;
        }
        if (paramVersions.isEmpty()) {
            return null;
        }
        VersionCalendarRepository.VersionWindow w = calendar.aggregateWindow(windowVersions);
        return new VersionResolution(List.copyOf(paramVersions), w == null ? null : w.start(),
                w == null ? null : w.end());
    }

    private List<String> safeCurrentVersions() {
        if (currentVersionsSupplier == null) {
            return List.of();
        }
        try {
            List<String> v = currentVersionsSupplier.get();
            return v == null ? List.of() : v;
        } catch (RuntimeException e) {
            log.warn("VersionPlanAnchorResolver: 在建版本查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 在建锚点 = currentVersions 里日历投产日最大的版本；都不在日历/为空返回 null。 */
    private String lastInBuildAnchor() {
        List<String> current = safeCurrentVersions();
        if (current.isEmpty()) {
            return null;
        }
        Map<String, VersionCalendarRepository.VersionWindow> snap = calendar.snapshot();
        String best = null;
        String bestEnd = null;
        for (String v : current) {
            VersionCalendarRepository.VersionWindow w = v == null ? null : snap.get(v.trim());
            if (w == null) {
                continue;
            }
            if (bestEnd == null || w.end().compareTo(bestEnd) > 0) {
                best = v.trim();
                bestEnd = w.end();
            }
        }
        return best;
    }

    /** @yyyy 年份限定年；无限定用执行期当前年。 */
    private static int anchorYear(Integer qualifierYear, LocalDate today) {
        return qualifierYear != null ? qualifierYear : today.getYear();
    }

    private static int parseLastN(String anchor) {
        try {
            return Integer.parseInt(anchor.substring("LAST:".length(), anchor.length() - 1));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static List<String> monthVersions(int year, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int m = from; m <= to; m++) {
            out.add(year + "年" + m + "月份版本");
        }
        return out;
    }

    /** "2026年4季度" → 该季度 3 个月的月份版本（§2.4：季度时间窗口口径，非季度→版本维度转换）。 */
    private static List<String> quarterToMonthVersions(String quarter) {
        java.util.regex.Matcher m = QUARTER_NUM.matcher(quarter);
        if (!m.matches()) {
            return List.of();
        }
        int q = Integer.parseInt(m.group(2));
        return monthVersions(Integer.parseInt(m.group(1)), (q - 1) * 3 + 1, q * 3);
    }

    /** 诊断口。 */
    public Map<String, VersionCalendarRepository.VersionWindow> calendarSnapshot() {
        return calendar.snapshot();
    }
}
