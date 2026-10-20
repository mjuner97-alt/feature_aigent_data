package com.agentscopea2a.v2.dimension;

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
 */
public final class AliasRegexSupport {

    /** value 模板中的捕获组引用（$1~$9）。 */
    private static final Pattern CAPTURE_REF = Pattern.compile("\\$(\\d)");

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

    /** $n → group(n)；任一引用组为 null 或越界返回 null（该次命中无效）。 */
    private static String substitute(String valueTemplate, Matcher m) {
        Matcher ref = CAPTURE_REF.matcher(valueTemplate);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (ref.find()) {
            int group = Integer.parseInt(ref.group(1));
            if (group > m.groupCount() || m.group(group) == null) {
                return null;
            }
            sb.append(valueTemplate, last, ref.start()).append(m.group(group));
            last = ref.end();
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
