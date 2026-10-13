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
package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillRoutingMetadata;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skill-Skill 功能重叠检测 (docs/history/skill-skill-overlap-plan.md §3.2, 三层一致性规则)。
 *
 * <p>判定哲学: Agent 选 Skill 链路 (问题 → keywords 命中入候选集 → domainTags 互斥过滤/排序
 * → 对候选集内 name+description 二选一) 中, 两 Skill 只有可能出现在 <b>同一候选集</b> 里被二选一。
 * <ul>
 * <li>T0 共现前提: 入口词 (keywords ∪ skillName) 归一化后存在相等或包含关系的词对
 *     (keyword 匹配语义是"问题文本包含该词", 问题含长词必含短词); 双方入口词均无交集 →
 *     永远不会同时入候选 → 不构成重复。</li>
 * <li>三层一致性: L1 领域标签 (domainTags) / L2 关键词 (keywords, 集合相等; skillName 是主键
 *     必然不同故不入集合比较) / L3 描述 (cosine ≥ cosineHighThreshold 视为无差异),
 *     任一层有不同即判断两个 Skill 不一致; 三层全部无差异才构成重复 (HIGH)。
 *     topicTags 仅内部管理归类不参与匹配, 不作为差异层。</li>
 * <li>degraded (embedding 不可用) 或一方描述为空时 L3 无证据, HIGH 降级为
 *     L1∧L2 相等 且 名称高度相近 (levenshtein ≥ {@value #NAME_HARD_THRESHOLD}, 名称是
 *     Agent 直接看到的选型依据, 作硬证据替代 cosine)。</li>
 * </ul>
 *
 * <p>分级: HIGH = T0 且三层全部无差异 (degraded 时按名称硬证据);
 * MEDIUM = T0 且 (cosine ≥ cosineThreshold 或名称相近) 但存在差异层 (suggestion 点名);
 * LOW = 仅名称相似 (命名卫生), 或 T0 共现词 ≥ 2 但语义/名称信号均未命中 (巡检)。
 *
 * <p>范围: 联合存活判定与 {@link SkillToolOverlapService} 一致 (skill_index 黑名单 = 软删
 * 必须排除; skill_manage 为权威; 两边都没有才是真孤儿); 已配置未 active 的 Skill 也参与
 * 配对 (启用前预警), 双方均未 active 的对是纯噪音不报; 单侧 active 照报 (启用互斥需要
 * 看到"本侧未 active、对侧已 active"的对)。
 *
 * <p>结果为派生数据, 进程内 TTL 缓存; 路由元数据保存后主动失效。重叠不落库。
 */
public class SkillSkillOverlapService {

    /** degraded 下名称硬证据阈值 (比 T3 命名卫生的 nameSimilarityThreshold 更严)。 */
    static final double NAME_HARD_THRESHOLD = 0.9;

    private final SkillRoutingMetadataRepository skillRoutingMetadataRepository;
    private final SkillIndexRepository skillIndexRepository;
    private final GovernanceEmbeddingCache embeddingCache;
    private final SkillDescriptionSource skillDescriptionSource;

    private final double cosineThreshold;
    private final double cosineHighThreshold;
    private final double nameSimilarityThreshold;

    private final long cacheTtlMillis;
    private volatile List<SkillSkillOverlapView> cached;
    private volatile boolean cachedDegraded;
    private volatile long cachedAt;

    public SkillSkillOverlapService(
            SkillRoutingMetadataRepository skillRoutingMetadataRepository,
            SkillIndexRepository skillIndexRepository,
            GovernanceEmbeddingCache embeddingCache,
            SkillDescriptionSource skillDescriptionSource,
            double cosineThreshold,
            double cosineHighThreshold,
            double nameSimilarityThreshold,
            long cacheTtlMillis) {
        this.skillRoutingMetadataRepository = skillRoutingMetadataRepository;
        this.skillIndexRepository = skillIndexRepository;
        this.embeddingCache = embeddingCache;
        this.skillDescriptionSource = skillDescriptionSource;
        this.cosineThreshold = cosineThreshold;
        this.cosineHighThreshold = cosineHighThreshold;
        this.nameSimilarityThreshold = nameSimilarityThreshold;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 路由元数据保存成功后调用, 让管理页修改立即生效。 */
    public void invalidate() {
        cached = null;
    }

    public record OverlapReport(boolean degraded, List<SkillSkillOverlapView> items) {}

    public synchronized OverlapReport report() {
        List<SkillSkillOverlapView> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < cacheTtlMillis) {
            return new OverlapReport(cachedDegraded, snapshot);
        }
        // 不等全局 warm(): 启动审计先于后台预热完成, 只要有 provider 就逐对尝试取向量,
        // 向量缺失的对自然降级为纯文本信号 (cosineKnown=false)。
        boolean degraded = embeddingCache == null || !embeddingCache.semanticAvailable();
        List<SkillSkillOverlapView> items = compute(degraded);
        cached = items;
        cachedDegraded = degraded;
        cachedAt = System.currentTimeMillis();
        return new OverlapReport(degraded, items);
    }

    /**
     * 同步预热 Skill 描述向量 (仅 block-skill-overlap=true 的启动审计调用, 避免启动期
     * 被后台预热未完成拖成 degraded 而漏判 cosine 证据)。命中缓存无 IO, 未命中逐条 embed。
     */
    public void ensureSkillVectorsWarmed() {
        if (embeddingCache == null || !embeddingCache.semanticAvailable()) {
            return;
        }
        for (ResolvedSkill skill : scanScope()) {
            embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.SKILL, skill.skillName(), skill.declaration());
        }
    }

    public record OverlapSummary(
            boolean degraded,
            Map<String, Long> counts,
            Map<String, Long> highBySkill) {

        public long high() {
            return counts.getOrDefault("HIGH", 0L);
        }
    }

    public OverlapSummary summary() {
        OverlapReport report = report();
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> highBySkill = new HashMap<>();
        for (SkillSkillOverlapView item : report.items()) {
            counts.merge(item.level(), 1L, Long::sum);
            if ("HIGH".equals(item.level())) {
                highBySkill.merge(item.skillNameA(), 1L, Long::sum);
                highBySkill.merge(item.skillNameB(), 1L, Long::sum);
            }
        }
        return new OverlapSummary(report.degraded(), counts, highBySkill);
    }

    /** 配对前的单个 Skill 解析结果: 联合存活判定 + declaration/owner 回退 + 入口词集合。 */
    private record ResolvedSkill(
            String skillName, String declaration, String owner, boolean active,
            List<String> keywords, List<String> domainTags, Set<String> aliases) {}

    private List<ResolvedSkill> scanScope() {
        List<ResolvedSkill> scope = new ArrayList<>();

        List<SkillRoutingMetadata> skills = skillRoutingMetadataRepository.findAll();

        // skill_manage 权威行 (仅 ACTIVE 且未删), 按 retrieval_name 索引; 一次查询避免 N+1。
        Map<String, SkillDescriptionSource.SkillDescriptionRow> manageRows = new HashMap<>();
        for (SkillDescriptionSource.SkillDescriptionRow row : skillDescriptionSource.allActiveSkills()) {
            manageRows.put(row.retrievalName(), row);
        }

        for (SkillRoutingMetadata skill : skills) {
            SkillEntry entry = skillIndexRepository.findByName(skill.skillName()).orElse(null);
            SkillDescriptionSource.SkillDescriptionRow manageRow = manageRows.get(skill.skillName());

            // 联合存活判定 (与 SkillToolOverlapService 一致):
            //  - skill_index 黑名单 = 软删标记 (页面 Skill 删除时 markBlacklist 写入), 排除;
            //  - 页面 Skill 以 skill_manage 为权威 (skill_index 行缺失 = 桥接失败/历史数据, 不算孤儿);
            //  - 工作台/内置 Skill 只存在于 skill_index, 两边都没有才是真孤儿路由行。
            boolean indexBlacklisted = entry != null && !SkillEntry.STATUS_ACTIVE.equals(entry.status());
            if (indexBlacklisted) {
                continue;
            }
            boolean indexActive = entry != null && SkillEntry.STATUS_ACTIVE.equals(entry.status());
            if (manageRow == null && !indexActive) {
                continue;
            }

            String declaration = !skill.shortSummary().isBlank()
                    ? skill.shortSummary()
                    : manageRow != null && manageRow.description() != null
                            ? manageRow.description()
                            : (entry == null || entry.description() == null ? "" : entry.description());
            if (declaration.isBlank()) {
                continue;
            }
            String owner = skill.creator() != null && !skill.creator().isBlank()
                    ? skill.creator()
                    : manageRow != null && manageRow.ownerUserId() != null && !manageRow.ownerUserId().isBlank()
                            ? manageRow.ownerUserId()
                            : skillRoutingMetadataRepository.creatorForSkill(skill.skillName());
            scope.add(new ResolvedSkill(skill.skillName(), declaration, owner, skill.active(),
                    skill.keywords(), skill.domainTags(), aliases(skill, entry, manageRow)));
        }
        return scope;
    }

    private List<SkillSkillOverlapView> compute(boolean degraded) {
        List<ResolvedSkill> skills = scanScope();

        // L3 向量: 非 degraded 时逐 Skill 取向量一次, O(n) 次 embed (命中缓存)。
        Map<String, float[]> vectors = new HashMap<>();
        if (!degraded) {
            for (ResolvedSkill skill : skills) {
                vectors.put(skill.skillName(), embeddingCache.embeddingFor(
                        GovernanceEmbeddingCache.EntityType.SKILL, skill.skillName(), skill.declaration()));
            }
        }

        List<SkillSkillOverlapView> result = new ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            for (int j = i + 1; j < skills.size(); j++) {
                SkillSkillOverlapView view = evaluatePair(skills.get(i), skills.get(j),
                        vectors, degraded);
                if (view != null) {
                    result.add(view);
                }
            }
        }
        result.sort(Comparator.comparing(SkillSkillOverlapView::level)
                .thenComparing(SkillSkillOverlapView::skillNameA)
                .thenComparing(SkillSkillOverlapView::skillNameB));
        return List.copyOf(result);
    }

    private SkillSkillOverlapView evaluatePair(ResolvedSkill a, ResolvedSkill b,
                                               Map<String, float[]> vectors, boolean degraded) {
        // 双方都未 active 的对不参与比较 (都不在路由候选, 无选型风险)
        if (!a.active() && !b.active()) {
            return null;
        }

        // T3 名称相似 (与 T0 无关, 单独命中只给 LOW 命名卫生)
        String normalizedA = TextSimilarityUtil.normalizeName(a.skillName());
        String normalizedB = TextSimilarityUtil.normalizeName(b.skillName());
        boolean nameHit = !normalizedA.isEmpty() && !normalizedB.isEmpty()
                && TextSimilarityUtil.levenshteinSimilarity(normalizedA, normalizedB)
                        >= nameSimilarityThreshold;
        boolean nameHardHit = !normalizedA.isEmpty() && !normalizedB.isEmpty()
                && TextSimilarityUtil.levenshteinSimilarity(normalizedA, normalizedB)
                        >= NAME_HARD_THRESHOLD;

        // T0 共现前提: 入口词 (keywords ∪ skillName) 归一化后存在相等或包含关系的词对
        // (keyword 匹配语义是"问题包含该词", 问题含长词必含短词, 二者同时命中)
        List<String> keywordOverlap = cooccurringTerms(a.aliases(), b.aliases());
        boolean t0 = !keywordOverlap.isEmpty();

        boolean cosineKnown = false;
        double cosine = 0;
        if (!degraded) {
            float[] va = vectors.get(a.skillName());
            float[] vb = vectors.get(b.skillName());
            if (va != null && vb != null) {
                cosine = TextSimilarityUtil.cosine(va, vb);
                cosineKnown = true;
            }
        }

        // 三层一致性: L1 领域标签 / L2 关键词 (集合相等) + L3 描述 (cosine >= 0.88)。
        // 任一层"有不同"即差异层; 三层全部无差异才 HIGH。
        // skillName 是主键必然不同, 不入 L2 集合比较 (否则任何一对都出差异层)。
        List<String> differingLayers = new ArrayList<>();
        if (!new HashSet<>(a.domainTags()).equals(new HashSet<>(b.domainTags()))) {
            differingLayers.add("领域标签");
        }
        if (!new HashSet<>(a.keywords()).equals(new HashSet<>(b.keywords()))) {
            differingLayers.add("关键词");
        }
        // degraded/描述缺失时 L3 无证据, 不单独构成差异, 由名称硬证据兜底
        boolean descriptionDiffers = cosineKnown && cosine < cosineHighThreshold;
        if (descriptionDiffers) {
            differingLayers.add("描述");
        }

        boolean highEvidence = t0 && differingLayers.isEmpty() && (cosineKnown || nameHardHit);

        String level = null;
        if (highEvidence) {
            level = "HIGH";
        } else if (t0 && ((cosineKnown && cosine >= cosineThreshold) || nameHit)
                && !differingLayers.isEmpty()) {
            level = "MEDIUM";
        } else if (nameHit || (t0 && keywordOverlap.size() >= 2)) {
            level = "LOW";
        }
        if (level == null) {
            return null;
        }

        String suggestion;
        if ("HIGH".equals(level) && cosineKnown) {
            suggestion = "Skill " + a.skillName() + " 与 Skill " + b.skillName()
                    + " 领域标签、关键词、描述完全重叠（cosine="
                    + String.format(Locale.ROOT, "%.2f", cosine)
                    + "），Agent 在同一候选集内无法区分，请合并为一个 Skill 或拆分关键词/改写描述";
        } else if ("HIGH".equals(level)) {
            suggestion = "Skill " + a.skillName() + " 与 Skill " + b.skillName()
                    + " 领域标签、关键词完全重叠且名称高度相近（embedding 不可用，仅名称判定）"
                    + "，Agent 在同一候选集内无法区分，请合并为一个 Skill 或拆分关键词/改写描述";
        } else if ("MEDIUM".equals(level)) {
            suggestion = "Skill " + a.skillName() + " 与 Skill " + b.skillName()
                    + " 同候选集（共现词: " + String.join("、", keywordOverlap) + "）内"
                    + layerDiffText(differingLayers, a, b, cosineKnown, cosine)
                    + "，请人工确认是否重复";
        } else if (nameHit) {
            suggestion = "Skill " + a.skillName() + " 与 Skill " + b.skillName()
                    + " 名称高度相似（命名卫生），请确认是否重复创建";
        } else {
            suggestion = "共现词相关（" + String.join("、", keywordOverlap) + "），供巡检参考";
        }

        return new SkillSkillOverlapView(
                a.skillName(), a.owner(),
                b.skillName(), b.owner(),
                level,
                List.copyOf(a.domainTags()),
                List.copyOf(b.domainTags()),
                List.copyOf(a.keywords()),
                List.copyOf(b.keywords()),
                List.copyOf(keywordOverlap),
                cosine,
                cosineKnown,
                List.copyOf(differingLayers),
                suggestion,
                a.active(),
                b.active());
    }

    /** 差异层明细文案, 如 "关键词不同（A: 缺陷,统计；B: 缺陷,趋势）、描述相近但有差异（cosine=0.85）"。 */
    private String layerDiffText(List<String> differingLayers, ResolvedSkill a, ResolvedSkill b,
                                 boolean cosineKnown, double cosine) {
        List<String> parts = new ArrayList<>();
        for (String layer : differingLayers) {
            switch (layer) {
                case "领域标签" -> parts.add("领域标签不同（A: " + String.join("、", a.domainTags())
                        + "；B: " + String.join("、", b.domainTags()) + "）");
                case "关键词" -> parts.add("关键词不同（A: " + String.join("、", a.keywords())
                        + "；B: " + String.join("、", b.keywords()) + "）");
                case "描述" -> {
                    String formatted = String.format(Locale.ROOT, "%.2f", cosine);
                    if (cosineKnown && cosine >= cosineThreshold) {
                        parts.add("描述相近但有差异（cosine=" + formatted + "）");
                    } else {
                        parts.add("描述可区分（cosine=" + formatted + "）");
                    }
                }
                default -> parts.add(layer + "不同");
            }
        }
        return String.join("、", parts);
    }

    /**
     * T0 共现词: 两侧入口词归一化后相等或存在包含关系的词对。
     * 相等输出词本身; 包含输出 "长词⊃短词" (问题含长词必含短词, 二者同时命中)。
     */
    static List<String> cooccurringTerms(Set<String> aliasesA, Set<String> aliasesB) {
        record Entry(String raw, String normalized) {}
        List<Entry> entriesA = aliasesA.stream()
                .map(v -> new Entry(v, normalizeTerm(v))).filter(e -> !e.normalized().isEmpty()).toList();
        List<Entry> entriesB = aliasesB.stream()
                .map(v -> new Entry(v, normalizeTerm(v))).filter(e -> !e.normalized().isEmpty()).toList();
        Set<String> out = new LinkedHashSet<>();
        for (Entry ea : entriesA) {
            for (Entry eb : entriesB) {
                if (ea.normalized().equals(eb.normalized())) {
                    out.add(ea.raw());
                } else if (ea.normalized().contains(eb.normalized())) {
                    out.add(ea.raw() + "⊃" + eb.raw());
                } else if (eb.normalized().contains(ea.normalized())) {
                    out.add(eb.raw() + "⊃" + ea.raw());
                }
            }
        }
        return List.copyOf(out);
    }

    /** 入口词归一化: 与 SkillCandidateSelector 的匹配语义一致 (lowercase + trim)。 */
    private static String normalizeTerm(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    /** 入口词可比对集合: 路由 keywords + skillName + skill_index/skill_manage 两侧行的名称。 */
    private static Set<String> aliases(
            SkillRoutingMetadata skill, SkillEntry entry,
            SkillDescriptionSource.SkillDescriptionRow manageRow) {
        Set<String> out = new LinkedHashSet<>(skill.keywords());
        out.add(skill.skillName());
        if (entry != null && entry.name() != null) out.add(entry.name());
        if (manageRow != null && manageRow.name() != null) out.add(manageRow.name());
        return out;
    }
}
