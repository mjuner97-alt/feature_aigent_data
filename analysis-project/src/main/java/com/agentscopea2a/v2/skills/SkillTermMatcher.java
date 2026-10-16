package com.agentscopea2a.v2.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 路由词条匹配的公共静态工具（§8.5 词条正则匹配共享化）：skill 四层词条、检索旁路词
 * 共用同一套语义（拍板⑤），两处行为必须一致，禁止各自实现。
 *
 * <p>语义（docs/history/skill-retrieval-logic.md §7.4）：词条 lowercase+trim 后可编译为
 * 正则则按正则 {@code find()} 解释（CASE_INSENSITIVE），编译失败回退字面 contains；
 * {@param question} 须为调用方已 lowercase 的规范化文本。
 */
public final class SkillTermMatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillTermMatcher.class);

    /** 编译成功的正则; Optional.empty() 表示非法正则（按字面包含处理），缓存避免重复编译报错。 */
    private static final ConcurrentHashMap<String, Optional<Pattern>> PATTERN_CACHE = new ConcurrentHashMap<>();

    private SkillTermMatcher() {
    }

    /** 词条按正则匹配（CASE_INSENSITIVE）; 编译失败回退字面包含，不抛异常。 */
    public static boolean matches(String normalizedQuestion, String term) {
        if (term == null) return false;
        String trimmed = term.trim();
        if (trimmed.isEmpty()) return false;
        Optional<Pattern> compiled = PATTERN_CACHE.computeIfAbsent(trimmed, t -> {
            try {
                return Optional.of(Pattern.compile(t, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                log.debug("Invalid routing keyword regex '{}', falling back to literal match: {}", t, e.getMessage());
                return Optional.empty();
            }
        });
        return compiled.map(p -> p.matcher(normalizedQuestion).find())
                .orElseGet(() -> normalizedQuestion.contains(normalize(term)));
    }

    /** 词条显式点名语义（skill 名匹配）：lowercase+trim 后字面 contains。 */
    public static boolean containsName(String normalizedQuestion, String name) {
        String normalized = normalize(name);
        return !normalized.isEmpty() && normalizedQuestion.contains(normalized);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
