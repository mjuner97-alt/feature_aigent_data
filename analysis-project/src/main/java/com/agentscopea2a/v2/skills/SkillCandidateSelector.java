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
 * <p>匹配语义: 分级关键词加权打分 (level1Keywords → level2Keywords → level3Keywords →
 * keywords 叶层)。**任一层命中即入围** (2026/10/13 定稿, 用户拍板): 问题命中配置的
 * 任何一层 (含最深层) 都让该 skill 入围, 层级只影响排序不过滤 —— 深层命中分更高
 * (问得越具体, 越精确的 skill 排越前), 浅层/叶层命中兜底可见。全部层都未命中才
 * 淘汰 (返回空集合, LLM 直接走工具)。显式点名 skillName 直接入围且排最前。
 * 榜首候选存在配置了却未命中的层 (命中链不完整) → 问题宽泛可信度低, 截断走放宽档。
 * 词条支持正则语法 (编译失败回退字面包含)。领域标签 {@code domainTags} 仅做互斥域
 * 过滤 (问题命中领域时排除其他领域的 skill, 通用 skill 始终保留) 与排序加成
 * (领域命中的 skill 排在通用 skill 之前); {@code topicTags} 仅内部管理归类,
 * 不参与匹配。层级分 L1=500 / L2=1000 / L3=1500, 叶层 keywords 维持 500/词 cap 1500。
 * 截断两档: 榜首命中链完整 (或显式点名, 问题精确) 截到 maxVisibleSkills; 榜首链不完整
 * (问题宽泛) 放宽到 fallbackVisibleSkills。
 */
public class SkillCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(SkillCandidateSelector.class);

    private static final int EXPLICIT_NAME_SCORE = 10_000;
    /** 高于关键词命中分上限 (1500) + L3 (1500), 保证领域命中的 skill 排在所有通用 skill 之前。 */
    private static final int DOMAIN_MATCH_SCORE = 3_000;
    private static final int LEVEL1_SCORE = 500;
    private static final int LEVEL2_SCORE = 1_000;
    private static final int LEVEL3_SCORE = 1_500;
    private static final int KEYWORD_SCORE_PER_HIT = 500;
    private static final int KEYWORD_SCORE_CAP = 1_500;

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
        String normalizedQuestion = normalize(question);
        Set<String> requestedDomains = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::domainTags);

        List<Candidate> pool = new ArrayList<>();
        boolean explicitAny = false;
        for (SkillRoutingMetadata entry : metadata) {
            if (!entry.active() || !availableNames.contains(entry.skillName())) {
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

        // 任一层命中即入围 (层级加权, 不过滤); 全部层都未命中才淘汰。
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
                    c.keywordHits(), c.domainScore() > 0, c.explicit(), !c.explicit() && partiallyMatched(c)));
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
        log.info("SkillCandidateSelector: question='{}', pool={}, fallback={}, limit={}, selected={}",
                abbreviate(question), pool.size(), hadFallback, limit, selected);
        return new SkillCandidateSelection(selected, explicitAny, true, scored.size() > limit);
    }

    /** 配置了某层却未命中 (靠其他层命中入围) → 命中链不完整。 */
    private static boolean partiallyMatched(Candidate c) {
        SkillRoutingMetadata e = c.entry();
        return (!e.level1Keywords().isEmpty() && !c.l1Hit())
                || (!e.level2Keywords().isEmpty() && !c.l2Hit())
                || (!e.level3Keywords().isEmpty() && !c.l3Hit())
                || (!e.keywords().isEmpty() && c.keywordHits() == 0);
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
                               boolean explicit, boolean partial) {}

    private record Candidate(SkillRoutingMetadata entry, boolean explicit, int keywordHits,
                             boolean l1Hit, boolean l2Hit, boolean l3Hit, int domainScore) {}
}
