package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Deterministic, keyword-driven selector for model-visible Skill candidates.
 *
 * <p><b>按 skill 自身配置层级的 AND + 双轨并存（2026/10/22 四次定稿）</b>，取代
 * 2026/10/14 的池级收窄（narrowByLevels）与 §8 拍板⑥（阶段一命中时叶层-only 出局）：
 * <ul>
 *   <li><b>显式点名</b>：问题 contains skill 名 → 直接入池恒置顶，豁免 AND。</li>
 *   <li><b>层级轨（配置了 L1/L2/L3 任一层）</b>：每个配置了的层都必须有词条命中问题
 *       （AND，未配置层不设要求），任一配置层未命中 → 该 skill 本轮完全出局。
 *       L2/L3 含保留字「保底」且上层命中时由注入机制供给命中（分组保底不变）。
 *       <b>domain 点名收紧（2026/10/28 方案 / 10/29 用户拍板）</b>：问题命中任一
 *       domain_tags 时，两条轨都只保留配了命中领域标签的 skill —— 未配领域标签
 *       （通用）的 skill 一并出局，层级关键词命中也不例外；显式点名仍豁免。</li>
 *   <li><b>存量轨（三层全空，仅 domain+keywords）</b>：行为与 2026/10/13 之前完全一致
 *       —— domain 互斥域过滤 + 叶层 keywords 命中打分（2000/词 cap 4000）+ domain
 *       排序加成；不再被层级 skill 的命中挤出。2026/10/28 起 domain 命中的 skill
 *       免叶层 keyword 要求入围（domain 命中即入围 = 领域点名是归属声明）。</li>
 *   <li><b>合并排序</b>：显式点名 &gt; domainHit（2026/10/28, 漏斗第 2 层 domain 命中
 *       &gt; 直接执行, 小模型按目录顺序选 skill, 域内 skill 必须排在层级轨前）&gt;
 *       层级轨（漏斗层深度 L3&gt;L2&gt;L1 &gt; 命中词全局位次）&gt; 存量轨（domain+叶层分）
 *       &gt; name。depth 天然分层（层级轨 1-3、存量轨 0）。domainHit 仅在
 *       requestedDomains 非空时可能为 true —— 无 domain 命中时该位比较零变化。</li>
 * </ul>
 *
 * <p>词条支持正则语法（编译失败回退字面包含，语义在 {@link SkillTermMatcher}，与旁路词共用）。
 * 全局关键词优先级（拍板⑧）由可选的 {@code prioritySupplier} 提供（表
 * {@code skill_keyword_priority}），空表 = 功能天然关闭。截断两档：榜首命中链完整
 * （或显式点名）截到 maxVisibleSkills，否则放宽到 fallbackVisibleSkills。
 */
public class SkillCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(SkillCandidateSelector.class);

    private static final int DOMAIN_MATCH_SCORE = 6_000;
    private static final int KEYWORD_SCORE_PER_HIT = 2_000;
    private static final int KEYWORD_SCORE_CAP = 4_000;

    /** 分组保底保留字（skill 二级/三级关键词格内精确等于该词 = 声明自己是组兜底，不参与问题匹配）。 */
    static final String FALLBACK_MARKER = "保底";

    private final int maxVisibleSkills;
    private final int fallbackVisibleSkills;
    /** 全局关键词优先级快照来源（拍板⑧）；null = 功能关闭。 */
    private final Supplier<List<SkillKeywordPriority>> prioritySupplier;
    /**
     * 维度实体 span 扫描器（docs/202610/检索关键词与维度值冲突治理方案.md §4）：
     * 词条命中落在实体 span 内 = 假主题信号不计分；null = 屏蔽关闭（行为同 2026/10/28 前）。
     */
    private final DimensionSpanScanner spanScanner;

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills) {
        this(maxVisibleSkills, fallbackVisibleSkills, null, null);
    }

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills,
                                  Supplier<List<SkillKeywordPriority>> prioritySupplier) {
        this(maxVisibleSkills, fallbackVisibleSkills, prioritySupplier, null);
    }

    public SkillCandidateSelector(int maxVisibleSkills, int fallbackVisibleSkills,
                                  Supplier<List<SkillKeywordPriority>> prioritySupplier,
                                  DimensionSpanScanner spanScanner) {
        this.maxVisibleSkills = Math.max(1, maxVisibleSkills);
        this.fallbackVisibleSkills = Math.max(this.maxVisibleSkills, fallbackVisibleSkills);
        this.prioritySupplier = prioritySupplier;
        this.spanScanner = spanScanner;
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
     * 共享同一套两阶段主体, 差别仅在无 availableNames 过滤。
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
        // 维度实体 span 屏蔽（§4）：一次扫描全池共用；scanner 缺席/空 span = 关闭
        List<int[]> entitySpans = spanScanner == null ? List.of() : spanScanner.scan(normalizedQuestion);
        Map<String, Integer> priorityIndex = buildPriorityIndex();

        // ── 分流: 显式点名 / 层级轨 (AND 判定) / 存量轨 (三层全空) ────────────────
        // domain 点名 (2026/10/28 方案, 2026/10/29 收紧): 问题命中任一 domain_tags →
        // 目录只保留命中领域的 skill, 未配领域标签(通用)的 skill 一并出局 — 层级关键词
        // 命中也不例外 (实证: 「部门例会 三部遗留问题」不应带出无领域标签的层级轨 skill)。
        // requestedDomains 为空时 domainActive=false, 全部既有行为零变化。
        Set<String> requestedDomains = matchedTags(normalizedQuestion, metadata, SkillRoutingMetadata::domainTags);
        boolean domainActive = !requestedDomains.isEmpty();
        List<Candidate> explicitPool = new ArrayList<>();
        List<Candidate> levelPool = new ArrayList<>();
        List<Candidate> restPool = new ArrayList<>();
        for (SkillRoutingMetadata entry : metadata) {
            if (!entry.active() || (nameFilter != null && !nameFilter.test(entry.skillName()))) {
                continue;
            }
            if (contains(normalizedQuestion, entry.skillName())) {
                explicitPool.add(new Candidate(entry, true, 0, List.of(), List.of(), List.of()));
                continue;
            }
            List<String> l1Terms = matchedTermsIn(normalizedQuestion, entry.level1Keywords(), entitySpans);
            List<String> l2Terms = matchedTermsIn(normalizedQuestion, entry.level2Keywords(), entitySpans);
            List<String> l3Terms = matchedTermsIn(normalizedQuestion, entry.level3Keywords(), entitySpans);
            injectFallbackHits(entry, l1Terms, l2Terms, l3Terms);
            boolean hasLevelConfig = !entry.level1Keywords().isEmpty() || !entry.level2Keywords().isEmpty()
                    || !entry.level3Keywords().isEmpty();
            if (hasLevelConfig) {
                // AND 语义 (2026/10/22 四次定稿): 每个配置了的层都必须有词条命中问题,
                // 任一配置层未命中 → 本轮完全出局 (显式点名已在上方豁免; 未配置层不设要求)。
                // domain 点名收紧: 命中领域时层级 skill 还须自身配了命中领域标签。
                if ((entry.level1Keywords().isEmpty() || !l1Terms.isEmpty())
                        && (entry.level2Keywords().isEmpty() || !l2Terms.isEmpty())
                        && (entry.level3Keywords().isEmpty() || !l3Terms.isEmpty())
                        && (!domainActive || hasAnyDomain(entry.domainTags(), requestedDomains))) {
                    levelPool.add(new Candidate(entry, false,
                            matchedTerms(normalizedQuestion, entry.keywords(), entitySpans),
                            l1Terms, l2Terms, l3Terms));
                }
                continue;
            }
            restPool.add(new Candidate(entry, false,
                    matchedTerms(normalizedQuestion, entry.keywords(), entitySpans),
                    l1Terms, l2Terms, l3Terms));
        }
        if (explicitPool.isEmpty() && levelPool.isEmpty() && restPool.isEmpty()) {
            return new SkillCandidateSelection(List.of(), false, false, false);
        }

        // ── 双轨合并打分: 层级轨 depth 1-3 排存量轨 depth 0 前, 单一比较器排序 ─────
        List<ScoredSkill> scored = new ArrayList<>();
        for (Candidate c : explicitPool) {
            scored.add(new ScoredSkill(c.entry().skillName(), true, 4, 0, false,
                    matchedTermsOf(normalizedQuestion, c.entry(), entitySpans), false, false));
        }
        for (Candidate c : levelPool) {
            int depth = c.l3Terms().isEmpty() ? c.l2Terms().isEmpty() ? 1 : 2 : 3;
            scored.add(new ScoredSkill(c.entry().skillName(), false, depth,
                    bestGlobalRank(priorityIndex, depth, c), partiallyMatched(c),
                    matchedTermsOf(normalizedQuestion, c.entry(), entitySpans), true, false));
        }
        for (Candidate c : restPool) {
            // domain 命中即入围 (2026/10/28 方案 §3.4, 拍板⑥): 领域点名 = 归属声明, 免叶层
            // keyword 要求 (现网叶层词是窄词如告警/遗留, 不含领域词「例会」, 不豁免则第 2 层落空)。
            // 2026/10/29 收紧: 命中领域时只保留配了命中领域标签的 skill — 其他领域标签与
            // 未配领域标签(通用)的 skill 均出局 (取代原「保留通用」的互斥域过滤语义)。
            // requestedDomains 空 → domainHit 恒 false, 原规则零变化。
            boolean domainHit = domainActive && hasAnyDomain(c.entry().domainTags(), requestedDomains);
            if (domainActive && !domainHit) {
                continue;
            }
            if (c.keywordHits() == 0 && !domainHit) {
                continue;
            }
            int domainScore = requestedDomains.isEmpty() || !hasDomainTags(c.entry().domainTags())
                    ? 0 : DOMAIN_MATCH_SCORE;
            int keywordScore = Math.min(KEYWORD_SCORE_CAP, KEYWORD_SCORE_PER_HIT * c.keywordHits());
            scored.add(new ScoredSkill(c.entry().skillName(), false, 0,
                    -(domainScore + keywordScore), false,
                    matchedTermsOf(normalizedQuestion, c.entry(), entitySpans), false, domainHit));
        }
        scored.sort(Comparator.comparing(ScoredSkill::explicit).reversed()
                .thenComparing(Comparator.comparing(ScoredSkill::domainHit).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::depth).reversed())
                .thenComparing(Comparator.comparingInt(ScoredSkill::globalRank))
                .thenComparing(ScoredSkill::name));
        return finish(scored, question, explicitPool.size() + levelPool.size() + restPool.size(), scored.size());
    }

    private SkillCandidateSelection finish(List<ScoredSkill> scored, String question,
                                           int prePoolSize, int postPoolSize) {
        // 两档截断看榜首: 排第一的候选命中链完整 (或显式点名) → 问题精确, 截到 maxVisibleSkills;
        // 榜首有配置了却未命中的层 (如只说了深层词) → 问题宽泛 (可信度低), 放宽到 fallbackVisibleSkills。
        boolean hadFallback = scored.isEmpty() || scored.get(0).partial();
        int limit = hadFallback ? fallbackVisibleSkills : maxVisibleSkills;
        List<String> selected = scored.stream().limit(limit).map(ScoredSkill::name).toList();
        List<String> topMatched = scored.isEmpty() ? List.of() : scored.get(0).matchedTerms();
        boolean topFromLevelTrack = !scored.isEmpty() && scored.get(0).levelTrack();
        log.info("SkillCandidateSelector: question='{}', pool={}, narrowedOut={}, fallback={}, limit={}, "
                        + "topFromLevelTrack={}, selected={}",
                abbreviate(question), prePoolSize, prePoolSize - postPoolSize, hadFallback, limit,
                topFromLevelTrack, selected);
        return new SkillCandidateSelection(selected, !selected.isEmpty() && scored.get(0).explicit(),
                true, scored.size() > limit, topMatched, topFromLevelTrack);
    }

    // ── 全局关键词优先级 (拍板⑧) ────────────────────────────────────────────

    /** level|keyword → 最小 sort_order (重复行取最优); 词未配置 → MAX_VALUE 排已配置词之后。 */
    private Map<String, Integer> buildPriorityIndex() {
        Map<String, Integer> index = new HashMap<>();
        List<SkillKeywordPriority> rows = prioritySupplier == null ? List.of() : prioritySupplier.get();
        if (rows == null) {
            return index;
        }
        for (SkillKeywordPriority row : rows) {
            if (row == null || !row.valid()) {
                continue;
            }
            String key = row.level() + "|" + normalize(row.keyword());
            index.merge(key, row.sortOrder() == null ? Integer.MAX_VALUE : row.sortOrder(), Math::min);
        }
        return index;
    }

    /** skill 在其最深命中层的最优命中词全局位次 (取各命中词位次最小者)。 */
    private static int bestGlobalRank(Map<String, Integer> index, int depth, Candidate c) {
        List<String> terms = depth == 3 ? c.l3Terms() : depth == 2 ? c.l2Terms() : c.l1Terms();
        int best = Integer.MAX_VALUE;
        for (String term : terms) {
            Integer rank = index.get(depth + "|" + normalize(term));
            if (rank != null && rank < best) {
                best = rank;
            }
        }
        return best;
    }

    // ── 词条/标签工具 ───────────────────────────────────────────────────────

    /** 各层真命中词条 (含叶层; 显式点名走空列表)。 */
    private static List<String> matchedTermsOf(String question, SkillRoutingMetadata entry, List<int[]> entitySpans) {
        List<String> matched = new ArrayList<>();
        collectMatched(question, entry.level1Keywords(), matched, entitySpans);
        collectMatched(question, entry.level2Keywords(), matched, entitySpans);
        collectMatched(question, entry.level3Keywords(), matched, entitySpans);
        collectMatched(question, entry.keywords(), matched, entitySpans);
        return matched;
    }

    private static List<String> matchedTermsIn(String question, List<String> terms, List<int[]> entitySpans) {
        List<String> matched = new ArrayList<>();
        collectMatched(question, terms, matched, entitySpans);
        return matched;
    }

    /**
     * 词条命中收集（span 屏蔽语义，docs/202610/检索关键词与维度值冲突治理方案.md §4.1）：
     * 每次 [start,end) 命中判实体 span 交集——存在任一 span 外命中即真命中；
     * 全部命中都落在实体 span 内 = 假主题信号，等同未命中。
     */
    private static void collectMatched(String question, List<String> terms, List<String> into,
                                       List<int[]> entitySpans) {
        if (terms == null) return;
        for (String term : terms) {
            if (isFallbackMarker(term)) {
                continue; // 保留字不参与问题文本匹配（只按注入规则生效）
            }
            if (entitySpans.isEmpty()) {
                if (SkillTermMatcher.matches(question, term)) {
                    into.add(term);
                }
                continue;
            }
            List<int[]> positions = SkillTermMatcher.matchPositions(question, term);
            boolean realHit = false;
            for (int[] pos : positions) {
                if (!DimensionSpanScanner.inSpans(pos[0], pos[1], entitySpans)) {
                    realHit = true;
                    break;
                }
            }
            if (realHit) {
                into.add(term);
            }
        }
    }

    private static boolean isFallbackMarker(String term) {
        return term != null && FALLBACK_MARKER.equals(term.trim());
    }

    /**
     * 分组保底注入（docs/202610/Skill检索分组保底方案.md §4.2）：L2 含保留字且 L1 真命中
     * → 注入 L2 命中；L3 含保留字且 L1+L2 均有命中词（L2 含注入）→ 注入 L3 命中。
     * 注入后收窄/深度/位次/截断全部走既有机制。
     */
    private static void injectFallbackHits(SkillRoutingMetadata entry, List<String> l1Terms,
                                           List<String> l2Terms, List<String> l3Terms) {
        if (l1Terms.isEmpty()) {
            return;
        }
        if (hasFallbackMarker(entry.level2Keywords()) && !l2Terms.contains(FALLBACK_MARKER)) {
            l2Terms.add(FALLBACK_MARKER);
        }
        if (!l2Terms.isEmpty() && hasFallbackMarker(entry.level3Keywords())
                && !l3Terms.contains(FALLBACK_MARKER)) {
            l3Terms.add(FALLBACK_MARKER);
        }
    }

    private static boolean hasFallbackMarker(List<String> terms) {
        if (terms == null) return false;
        return terms.stream().anyMatch(SkillCandidateSelector::isFallbackMarker);
    }

    private static int matchedTerms(String question, List<String> terms, List<int[]> entitySpans) {
        if (terms == null) return 0;
        int count = 0;
        for (String term : terms) {
            List<String> single = new ArrayList<>(1);
            collectMatched(question, List.of(term), single, entitySpans);
            if (!single.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 配置了叶层 keywords 却零命中 → 命中链不完整 (层级命中已由 AND 在入池前强制)。 */
    private static boolean partiallyMatched(Candidate c) {
        SkillRoutingMetadata e = c.entry();
        return !e.keywords().isEmpty() && c.keywordHits() == 0;
    }

    private static boolean hasDomainTags(List<String> tags) {
        return tags != null && tags.stream().anyMatch(tag -> tag != null && !tag.trim().isEmpty());
    }

    private static boolean hasAnyDomain(List<String> tags, Set<String> domains) {
        return tags != null && tags.stream().filter(java.util.Objects::nonNull)
                .map(SkillCandidateSelector::normalize).anyMatch(domains::contains);
    }

    private static boolean contains(String question, String value) {
        return SkillTermMatcher.containsName(question, value);
    }

    private static String normalize(String value) {
        return SkillTermMatcher.normalize(value);
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

    /**
     * 排序视图: 阶段一用 (explicit, domainHit, depth, globalRank, name); 阶段二把 domain+叶层分
     * 取负塞进 globalRank 复用同一比较器 (分数大者前 = 取负后小者前)。
     * domainHit 仅存量轨候选且 requestedDomains 非空且自身 domain 命中时为 true (§3.3)。
     */
    private record ScoredSkill(String name, boolean explicit, int depth, int globalRank,
                               boolean partial, List<String> matchedTerms, boolean levelTrack,
                               boolean domainHit) {}

    private record Candidate(SkillRoutingMetadata entry, boolean explicit, int keywordHits,
                             List<String> l1Terms, List<String> l2Terms, List<String> l3Terms) {}
}
