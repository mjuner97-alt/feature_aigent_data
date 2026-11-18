package com.agentscopea2a.v2.dimension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 动态维度键正则词表支持（docs/202610/dimension-key-dynamic-dimension-plan.md §6.4）：
 * 词表 value 含 {@code $1}~{@code $9} 时 alias 按正则匹配，命中后用捕获组替换
 * {@code $n} 产出维度值（如「流转次数>3」→ transferTimes=["3"]）；
 * value 不含 {@code $n} 的行维持 contains 语义，存量配置零影响。
 *
 * <p>保存期校验（DimensionKeyController）已保证正则可编译、组号存在；这里对
 * 手工改库等绕过路径做运行时防御：编译失败/组缺失的行跳过不产出，不抛异常。
 *
 * <p>补年令牌 {@code {year:$n}}（2026-11-17 投产日期动态维度）：value 中先替换
 * {@code {year:$n}} 再走 {@code $n} 替换——命中组非空取捕获值（2位年补 20 前缀），
 * 组为空（alias 年前缀可省）取当前年（Asia/Shanghai，同 TimeRangeResolver 时区口径）。
 * 裸日期"原词形态补不了年"（裸日期单日方案 拍板⑧）由此在配置层解决。
 */
public final class AliasRegexSupport {

    /** value 模板中的捕获组引用（$1~$9）。 */
    private static final Pattern CAPTURE_REF = Pattern.compile("\\$(\\d)");

    /** 补年令牌：{year:$n} —— $n 为年前缀捕获组，组为空取当前年。 */
    private static final Pattern YEAR_TOKEN = Pattern.compile("\\{year:\\$(\\d)}");

    private AliasRegexSupport() {
    }

    /** value 是否为正则捕获模板（含 $1~$9 引用）。 */
    public static boolean isRegexTemplate(String value) {
        return value != null && CAPTURE_REF.matcher(value).find();
    }

    /** 模板引用的最大组号；无引用返回 0。 */
    public static int maxCaptureGroup(String valueTemplate) {
        int max = 0;
        if (valueTemplate == null) {
            return 0;
        }
        Matcher m = CAPTURE_REF.matcher(valueTemplate);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    /**
     * 正则模式提取：find 循环逐个命中，$n → 捕获组，去重保序。
     * 编译失败 / 引用组缺失（null 或越界）→ 该次命中跳过；零宽命中终止防死循环。
     */
    public static List<String> extract(String question, String aliasRegex, String valueTemplate) {
        if (question == null || aliasRegex == null || valueTemplate == null) {
            return List.of();
        }
        Pattern p;
        try {
            p = Pattern.compile(aliasRegex);
        } catch (PatternSyntaxException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(question);
        while (m.find()) {
            if (m.end() == m.start()) {
                break; // 零宽匹配会在原位空转, 直接终止
            }
            String resolved = substitute(valueTemplate, m);
            if (resolved != null && !out.contains(resolved)) {
                out.add(resolved);
            }
        }
        return out;
    }

    /**
     * 模板替换：先解 {year:$n} 补年令牌（组空→当前年，2位年→20YY），
     * 再走 $n → group(n)；$n 任一引用组为 null 或越界返回 null（该次命中无效）。
     */
    private static String substitute(String valueTemplate, Matcher m) {
        String template = replaceYearTokens(valueTemplate, m);
        Matcher ref = CAPTURE_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (ref.find()) {
            int group = Integer.parseInt(ref.group(1));
            if (group > m.groupCount() || m.group(group) == null) {
                return null;
            }
            sb.append(template, last, ref.start()).append(m.group(group));
            last = ref.end();
        }
        sb.append(template, last, template.length());
        return sb.toString();
    }

    /** {year:$n} → 捕获年（2位补 20 前缀）；组为空/越界 → 当前年（Asia/Shanghai）。 */
    private static String replaceYearTokens(String valueTemplate, Matcher m) {
        if (!YEAR_TOKEN.matcher(valueTemplate).find()) {
            return valueTemplate;
        }
        String currentYear = String.valueOf(LocalDate.now(TimeRangeResolver.ZONE).getYear());
        Matcher t = YEAR_TOKEN.matcher(valueTemplate);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (t.find()) {
            int group = Integer.parseInt(t.group(1));
            String year = currentYear;
            if (group <= m.groupCount() && m.group(group) != null) {
                year = m.group(group);
                if (year.length() == 2) {
                    year = "20" + year;
                }
            }
            sb.append(valueTemplate, last, t.start()).append(year);
            last = t.end();
        }
        sb.append(valueTemplate, last, valueTemplate.length());
        return sb.toString();
    }

    /** 保存期校验：正则模式行必须可编译且引用组号不越界，非法返回错误描述（null=合法）。 */
    public static String validateRegexRow(String alias, String valueTemplate) {
        int maxGroup = maxCaptureGroup(valueTemplate);
        Pattern p;
        try {
            p = Pattern.compile(alias);
        } catch (PatternSyntaxException e) {
            return "词表 alias 不是合法正则: " + e.getMessage();
        }
        if (maxGroup > p.matcher("").groupCount()) {
            return "词表值引用了不存在的捕获组 $" + maxGroup
                    + "（alias 正则只有 " + p.matcher("").groupCount() + " 个捕获组）";
        }
        return null;
    }
}
