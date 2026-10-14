package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, keyword-driven selector for model-visible Skill candidates.
 *
 * <p>匹配语义: 分级关键词 (level1Keywords → level2Keywords → level3Keywords →
 * keywords 叶层) 两阶段处理。**预选**: 显式点名 skillName 或任一层 (含叶层) 至少一个
 * 词条正则命中问题才入池, 全不命中返回空集合 (LLM 直接走工具)。**收窄** (2026/10/14
 * 定稿, 用户拍板): 按 L1→L2→L3 逐层检查, 某层在池中有命中时, 配置了该层却未命中的
 * skill 出局 (即使叶层命中 —— 层配置是承诺不是可选项), 该层未配置 (空) 的 skill 视为
 * 通用直通; 该层无任何命中则跳过 (保住只说最具体词的深层反向入围)。显式点名的 skill
 * 豁免收窄淘汰。层级仍参与排序, 四层权重倒序 (2026/10/14 定稿): 叶层 2000/词 cap 4000
 * &gt; L3=1500 &gt; L2=1000 &gt; L1=500; 同一词条配置在多层且都命中时各层分数都拿
 * (跨层累加是有意行为, 不按不同词去重)。
 * 词条支持正则语法 (编译失败回退字面包含)。领域标签 {@code domainTags} 仅做互斥域
 * 过滤 (问题命中领域时排除其他领域的 skill, 通用 skill 始终保留) 与排序加成
 * (领域命中的 skill 排在通用 skill 之前); {@code topicTags} 仅内部管理归类,
 * 不参与匹配。截断两档: 榜首配置的每层都命中 (或显式点名/层未配置, 问题精确) 截到
 * maxVisibleSkills; 榜首有配置了却未命中的层 (问题宽泛) 放宽到 fallbackVisibleSkills。
 */
public class SkillCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(SkillCandidateSelector.class);

    private static final int EXPLICIT_NAME_SCORE = 20_000;
    /**
     * 高于通用 skill 得分上限 (三层全空, 只能拿叶层分 cap 4000), 保证领域命中的 skill
     * 排在所有通用 skill 之前; 同时低于 EXPLICIT_NAME_SCORE, 显式点名恒排最前。
     */
    private static final int DOMAIN_MATCH_SCORE = 6_000;
    /** 四层权重倒序 L4(叶层) > L3 > L2 > L1 (2026/10/14 三次定稿, docs/history/skill-retrieval-logic.md §7.3)。 */
    private static final int LEVEL1_SCORE = 500;
    private static final int LEVEL2_SCORE = 1_000;
    private static final int LEVEL3_SCORE = 1_500;
    private static final int KEYWORD_SCORE_PER_HIT = 2_000;
    private static final int KEYWORD_SCORE_CAP = 4_000;

    private final int maxVisibleSkills;
    private final int fallbackVisibleSkills;
    /** 编译成功的正则; Optional.empty() 表示非法正则 (按字面包含处理), 缓存避免重复编译报错。 */
    private static final ConcurrentHashMap<String, Optional<Pattern>> PATTERN_CACHE = new ConcurrentHashMap<>();

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills) {
        this.maxVisibleSkills = Math.max(1, maxVisibleSkills);
        this.fallbackVisibleSkills = Math.max(this.maxVisibleSkills, fallbackVisibleSkills);
    }

    public SkillCandidateSelection select(List<AgentSkill> allSkills,
                                           List<SkillRoutingMetadata> metadata,
                                           String question) {
        if (allSkills == null || allSkills.isEmpty() || metadata == null || metadata.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }
        Set<String> availableNames = allSkills.stream().map(AgentSkill::getName).collect(Collectors.toSet());
        return selectInternal(metadata, question, availableNames::contains);
    }

    /**
     * Metadata-only 入口 (Skill 直接执行路径用, 不加载 AgentSkill): 与 {@link #select}
     * 共享同一套入围/打分/排序主体, 差别仅在无 availableNames 过滤。
     * 调用方仍需自行做权限过滤后再传入。
     */
    public SkillCandidateSelection selectFromMetadata(List<SkillRoutingMetadata> metadata, String question) {
        if (metadata == null || metadata.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }
        return selectInternal(metadata, question, null);
    }

    private SkillCandidateSelection selectInternal(List<SkillRoutingMetadata> metadata, String question,
                                                    java.util.function.Predicate<String> nameFilter) {
        String normalizedQuestion = normalize(question);
        Set<String> requestedDomains = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::domainTags);

        List<Candidate> pool = new ArrayList<>();
        boolean explicitAny = false;
        for (SkillRoutingMetadata entry : metadata) {
            if (!entry.active() || (nameFilter != null && !nameFilter.test(entry.skillName()))) {
                continue;
            }
            boolean explicit = contains(normalizedQuestion, entry.skillName());
            if (explicit) {
                explicitAny = true;
            }
            int keywordHits = explicit ? 0 : matchedTerms(normalizedQuestion, entry.keywords());
            if (!explicit && !requestedDomains.isEmpty()
                    && hasDomainTags(entry.domainTags())
                    && !hasAnyDomain(entry.domainTags(), requestedDomains)) {
                continue;
            }
            int domainScore = requestedDomains.isEmpty() || !hasDomainTags(entry.domainTags())
                    ? 0 : DOMAIN_MATCH_SCORE;
            pool.add(new Candidate(entry, explicit, keywordHits,
                    !explicit && hitsAny(normalizedQuestion, entry.level1Keywords()),
                    !explicit && hitsAny(normalizedQuestion, entry.level2Keywords()),
                    !explicit && hitsAny(normalizedQuestion, entry.level3Keywords()),
                    domainScore));
        }
        if (pool.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }

        // 命中即逐层收窄 (2026/10/14 定稿): 某层有命中时, 配置了该层却未命中的 skill 出局。
        int preNarrowSize = pool.size();
        pool = narrowByLevels(pool);

        // 收窄只淘汰, 不新增; 入池者必已满足预选 (显式点名或任一层命中)。
        List<ScoredSkill> scored = new ArrayList<>();
        for (Candidate c : pool) {
            boolean admitted = c.explicit() || c.keywordHits() > 0 || c.l1Hit() || c.l2Hit() || c.l3Hit();
            if (!admitted) {
                continue;
            }
            int levelScore = (c.l1Hit() ? LEVEL1_SCORE : 0)
                    + (c.l2Hit() ? LEVEL2_SCORE : 0)
                    + (c.l3Hit() ? LEVEL3_SCORE : 0);
            int keywordScore = Math.min(KEYWORD_SCORE_CAP, KEYWORD_SCORE_PER_HIT * c.keywordHits());
            scored.add(new ScoredSkill(c.entry().skillName(),
                    (c.explicit() ? EXPLICIT_NAME_SCORE : 0) + c.domainScore() + levelScore + keywordScore,
                    c.keywordHits(), c.domainScore() > 0, c.explicit(), !c.explicit() && partiallyMatched(c),
                    c.explicit() ? List.of() : matchedTermsOf(normalizedQuestion, c.entry())));
        }
        scored.sort(Comparator.comparing(ScoredSkill::explicit).reversed()
                .thenComparing(Comparator.comparingInt(ScoredSkill::score).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::keywordHits).reversed())
                .thenComparing(ScoredSkill::name));
        // 两档截断看榜首: 排第一的候选命中链完整 (或显式点名) → 问题精确, 截到 maxVisibleSkills;
        // 榜首有配置了却未命中的层 (如只说了深层词) → 问题宽泛 (可信度低), 放宽到 fallbackVisibleSkills。
        boolean hadFallback = scored.isEmpty() || scored.get(0).partial();
        int limit = hadFallback ? fallbackVisibleSkills : maxVisibleSkills;
        List<String> selected = scored.stream().limit(limit).map(ScoredSkill::name).toList();
        List<String> topMatched = scored.isEmpty() ? List.of() : scored.get(0).matchedTerms();
        log.info("SkillCandidateSelector: question='{}', pool={}, narrowedOut={}, fallback={}, limit={}, selected={}",
                abbreviate(question), preNarrowSize, preNarrowSize - pool.size(), hadFallback, limit, selected);
        return new SkillCandidateSelection(selected, explicitAny, true, scored.size() > limit, topMatched);
    }

    /** 各层真命中词条 (定稿语义下入围者必为某层真命中; 显式点名走空列表)。 */
    private static List<String> matchedTermsOf(String question, SkillRoutingMetadata entry) {
        List<String> matched = new ArrayList<>();
        collectMatched(question, entry.level1Keywords(), matched);
        collectMatched(question, entry.level2Keywords(), matched);
        collectMatched(question, entry.level3Keywords(), matched);
        collectMatched(question, entry.keywords(), matched);
        return matched;
    }

    private static void collectMatched(String question, List<String> terms, List<String> into) {
        if (terms == null) return;
        for (String term : terms) {
            if (matches(question, term)) {
                into.add(term);
            }
        }
    }

    /** 配置了某层却未命中 (靠其他层命中入围) → 命中链不完整。 */
    private static boolean partiallyMatched(Candidate c) {
        SkillRoutingMetadata e = c.entry();
        return (!e.level1Keywords().isEmpty() && !c.l1Hit())
                || (!e.level2Keywords().isEmpty() && !c.l2Hit())
                || (!e.level3Keywords().isEmpty() && !c.l3Hit())
                || (!e.keywords().isEmpty() && c.keywordHits() == 0);
    }

    /**
     * 命中即逐层收窄 (2026/10/14 定稿): 按 L1→L2→L3 逐层检查, 池中 (非显式点名) 存在该层
     * 命中时, 淘汰"配置了该层却未命中"的 skill; 该层未配置 (空) 的 skill 直通; 该层无命中
     * 则整层跳过。显式点名的 skill 豁免淘汰 (也不触发收窄 —— 其层级命中位恒为 false)。
     */
    private static List<Candidate> narrowByLevels(List<Candidate> pool) {
        List<Candidate> current = pool;
        current = narrowAtLevel(current, Candidate::l1Hit, c -> c.entry().level1Keywords().isEmpty());
        current = narrowAtLevel(current, Candidate::l2Hit, c -> c.entry().level2Keywords().isEmpty());
        current = narrowAtLevel(current, Candidate::l3Hit, c -> c.entry().level3Keywords().isEmpty());
        return current;
    }

    private static List<Candidate> narrowAtLevel(List<Candidate> pool,
                                                 java.util.function.Predicate<Candidate> hit,
                                                 java.util.function.Predicate<Candidate> unconfigured) {
        boolean anyHit = pool.stream().filter(c -> !c.explicit()).anyMatch(hit);
        if (!anyHit) {
            return pool;
        }
        return pool.stream()
                .filter(c -> c.explicit() || hit.test(c) || unconfigured.test(c))
                .collect(Collectors.toList());
    }

    private static int matchedTerms(String question, List<String> terms) {
        if (terms == null) return 0;
        return (int) terms.stream().filter(term -> matches(question, term)).count();
    }

    private static boolean hitsAny(String question, List<String> terms) {
        if (terms == null) return false;
        return terms.stream().anyMatch(term -> matches(question, term));
    }

    /** 词条按正则匹配 (CASE_INSENSITIVE); 编译失败回退字面包含, 不抛异常。 */
    private static boolean matches(String question, String term) {
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
        return compiled.map(p -> p.matcher(question).find())
                .orElseGet(() -> question.contains(normalize(term)));
    }

    private static boolean hasDomainTags(List<String> tags) {
        return tags != null && tags.stream().anyMatch(tag -> tag != null && !tag.trim().isEmpty());
    }

    private static boolean hasAnyDomain(List<String> tags, Set<String> domains) {
        return tags != null && tags.stream().filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).anyMatch(domains::contains);
    }

    private static boolean contains(String question, String value) {
        String normalizedValue = normalize(value);
        return !normalizedValue.isEmpty() && question.contains(normalizedValue);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "...";
    }

    private static Set<String> matchedTags(String question, List<SkillRoutingMetadata> metadata,
                                           java.util.function.Function<SkillRoutingMetadata, List<String>> tags) {
        return metadata.stream().flatMap(entry -> tags.apply(entry).stream()).filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).filter(tag -> !tag.isEmpty() && question.contains(tag))
                .collect(Collectors.toSet());
    }

    private record ScoredSkill(String name, int score, int keywordHits, boolean domainMatched,
                               boolean explicit, boolean partial, List<String> matchedTerms) {}

    private record Candidate(SkillRoutingMetadata entry, boolean explicit, int keywordHits,
                             boolean l1Hit, boolean l2Hit, boolean l3Hit, int domainScore) {}
}
