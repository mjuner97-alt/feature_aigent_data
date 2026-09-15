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
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skill-Tool 功能重叠检测 (docs/skill-tool-overlap-and-description-similarity-plan.md §1)。
 *
 * <p>信号与分级 (S1 只佐证不单独定级 —— 两层架构下工作流 Skill 与其编排的工具共享
 * 业务主题 (topic_tags) 是正常现象):
 * <ul>
 * <li>HIGH   = S3 命中 (名称/keywords 归一化匹配 toolId, 或声明文本含 toolId 字面量)
 *             或 S2 ≥ cosineHighThreshold (0.88)</li>
 * <li>MEDIUM = S2 ≥ cosineThreshold (0.80) 且 S1 ≥ 1</li>
 * <li>LOW    = S2 单独达阈值, 或 S1 ≥ 2 (仅巡检)</li>
 * </ul>
 *
 * <p>范围: 路由 active 的 Skill 联合判定存活 (skill_index 黑名单 = 软删标记必须排除;
 * 页面 Skill 以 skill_manage ACTIVE+未删为权威, skill_index 行缺失不算孤儿;
 * 工作台/内置 Skill 只在 skill_index, 需 status='active'); enabled 的 Tool。
 *
 * <p>结果为派生数据, 进程内 TTL 缓存, 两侧元数据保存后主动失效; 重叠不落库。
 */
public class SkillToolOverlapService {

    private final SkillRoutingMetadataRepository skillRoutingMetadataRepository;
    private final SkillIndexRepository skillIndexRepository;
    private final ToolRoutingMetadataRepository toolRoutingMetadataRepository;
    private final GovernanceEmbeddingCache embeddingCache;
    private final SkillDescriptionSource skillDescriptionSource;

    private final double cosineThreshold;
    private final double cosineHighThreshold;
    private final double nameSimilarityThreshold;

    private final long cacheTtlMillis;
    private volatile List<RoutingOverlapView> cached;
    private volatile boolean cachedDegraded;
    private volatile long cachedAt;

    public SkillToolOverlapService(
            SkillRoutingMetadataRepository skillRoutingMetadataRepository,
            SkillIndexRepository skillIndexRepository,
            ToolRoutingMetadataRepository toolRoutingMetadataRepository,
            GovernanceEmbeddingCache embeddingCache,
            SkillDescriptionSource skillDescriptionSource,
            double cosineThreshold,
            double cosineHighThreshold,
            double nameSimilarityThreshold,
            long cacheTtlMillis) {
        this.skillRoutingMetadataRepository = skillRoutingMetadataRepository;
        this.skillIndexRepository = skillIndexRepository;
        this.toolRoutingMetadataRepository = toolRoutingMetadataRepository;
        this.embeddingCache = embeddingCache;
        this.skillDescriptionSource = skillDescriptionSource;
        this.cosineThreshold = cosineThreshold;
        this.cosineHighThreshold = cosineHighThreshold;
        this.nameSimilarityThreshold = nameSimilarityThreshold;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 两侧元数据保存成功后调用, 让管理页修改立即生效。 */
    public void invalidate() {
        cached = null;
    }

    public record OverlapReport(boolean degraded, List<RoutingOverlapView> items) {}

    public synchronized OverlapReport report() {
        List<RoutingOverlapView> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < cacheTtlMillis) {
            return new OverlapReport(cachedDegraded, snapshot);
        }
        boolean degraded = !embeddingCache.semanticAvailable() || !embeddingCache.warm();
        List<RoutingOverlapView> items = compute(degraded);
        cached = items;
        cachedDegraded = degraded;
        cachedAt = System.currentTimeMillis();
        return new OverlapReport(degraded, items);
    }

    public record OverlapSummary(
            boolean degraded,
            Map<String, Long> counts,
            Map<String, Long> highBySkill,
            Map<String, Long> highByTool) {

        public long high() {
            return counts.getOrDefault("HIGH", 0L);
        }
    }

    public OverlapSummary summary() {
        OverlapReport report = report();
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> highBySkill = new HashMap<>();
        Map<String, Long> highByTool = new HashMap<>();
        for (RoutingOverlapView item : report.items()) {
            counts.merge(item.level(), 1L, Long::sum);
            if ("HIGH".equals(item.level())) {
                highBySkill.merge(item.skillName(), 1L, Long::sum);
                highByTool.merge(item.toolId(), 1L, Long::sum);
            }
        }
        return new OverlapSummary(report.degraded(), counts, highBySkill, highByTool);
    }

    private List<RoutingOverlapView> compute(boolean degraded) {
        List<RoutingOverlapView> result = new ArrayList<>();

        List<SkillRoutingMetadata> skills = skillRoutingMetadataRepository.findActive();
        List<ToolRoutingMetadata> tools = toolRoutingMetadataRepository.findEnabled();

        // skill_manage 权威行 (仅 ACTIVE 且未删), 按 retrieval_name 索引; 一次查询避免 N+1。
        Map<String, SkillDescriptionSource.SkillDescriptionRow> manageRows = new HashMap<>();
        for (SkillDescriptionSource.SkillDescriptionRow row : skillDescriptionSource.allActiveSkills()) {
            manageRows.put(row.retrievalName(), row);
        }

        for (SkillRoutingMetadata skill : skills) {
            SkillEntry entry = skillIndexRepository.findByName(skill.skillName()).orElse(null);
            SkillDescriptionSource.SkillDescriptionRow manageRow = manageRows.get(skill.skillName());

            // 联合存活判定:
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
            Set<String> skillAliases = aliases(skill, entry, manageRow);

            for (ToolRoutingMetadata tool : tools) {
                RoutingOverlapView view = evaluatePair(skill, declaration, owner, skillAliases, tool, degraded);
                if (view != null) {
                    result.add(view);
                }
            }
        }
        result.sort(Comparator.comparing(RoutingOverlapView::level)
                .thenComparing(RoutingOverlapView::skillName)
                .thenComparing(RoutingOverlapView::toolId));
        return List.copyOf(result);
    }

    private RoutingOverlapView evaluatePair(
            SkillRoutingMetadata skill,
            String declaration,
            String owner,
            Set<String> skillAliases,
            ToolRoutingMetadata tool,
            boolean degraded) {
        // S1: 业务主题交集 (佐证; skill 指标标签已随评分体系简化移除, 佐证改用两侧业务主题)
        Set<String> toolTopics = new HashSet<>(tool.topicTags());
        List<String> topicOverlap = skill.topicTags().stream()
                .filter(toolTopics::contains)
                .distinct()
                .toList();

        // S2: 描述语义相似
        double cosine = 0;
        if (!degraded) {
            float[] skillVec = embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.SKILL, skill.skillName(), declaration);
            float[] toolVec = embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.TOOL, tool.toolId(), tool.description());
            cosine = TextSimilarityUtil.cosine(skillVec, toolVec);
        }

        // S3: 名称/keywords 与 toolId 匹配 + 声明文本含 toolId 字面量
        boolean aliasHit = false;
        String normalizedToolId = TextSimilarityUtil.normalizeName(tool.toolId());
        for (String alias : skillAliases) {
            String normalizedAlias = TextSimilarityUtil.normalizeName(alias);
            if (!normalizedAlias.isEmpty()
                    && (normalizedAlias.equals(normalizedToolId)
                            || TextSimilarityUtil.levenshteinSimilarity(normalizedAlias, normalizedToolId)
                                    >= nameSimilarityThreshold)) {
                aliasHit = true;
                break;
            }
        }
        boolean toolIdLiteral = !tool.toolId().isBlank()
                && declaration.toLowerCase(Locale.ROOT).contains(tool.toolId().toLowerCase(Locale.ROOT));

        boolean s2 = cosine >= cosineThreshold;
        boolean s1 = !topicOverlap.isEmpty();
        String level = null;
        if (aliasHit || toolIdLiteral || cosine >= cosineHighThreshold) {
            level = "HIGH";
        } else if (s2 && s1) {
            level = "MEDIUM";
        } else if (s2 || topicOverlap.size() >= 2) {
            level = "LOW";
        }
        if (level == null) {
            return null;
        }

        String suggestion;
        if (aliasHit || toolIdLiteral) {
            suggestion = "该 Skill 与工具 " + tool.toolId()
                    + " 名称或描述直接对应；若 Skill 仅为工具枚举/转发，建议退役并将描述迁入工具路由元数据";
        } else if ("HIGH".equals(level)) {
            suggestion = "该 Skill 与工具 " + tool.toolId()
                    + " 能力声明高度相似；若 Skill 无独立业务规则，建议上收为工作流编排并修改描述";
        } else if ("MEDIUM".equals(level)) {
            suggestion = "该 Skill 与工具 " + tool.toolId() + " 描述相近且业务主题相关（"
                    + String.join("、", topicOverlap) + "）；请人工确认是否能力重复";
        } else {
            suggestion = "业务主题相关（" + String.join("、", topicOverlap) + "），供巡检参考";
        }

        return new RoutingOverlapView(
                skill.skillName(),
                owner,
                declaration,
                tool.toolId(),
                tool.toolType().name(),
                level,
                List.copyOf(topicOverlap),
                cosine,
                aliasHit,
                toolIdLiteral,
                suggestion);
    }

    /** 名称可比对集合: 路由 keywords + skillName + skill_index/skill_manage 两侧行的名称。 */
    private static Set<String> aliases(
            SkillRoutingMetadata skill, SkillEntry entry,
            SkillDescriptionSource.SkillDescriptionRow manageRow) {
        Set<String> out = new HashSet<>(skill.keywords());
        out.add(skill.skillName());
        if (entry != null && entry.name() != null) out.add(entry.name());
        if (manageRow != null && manageRow.name() != null) out.add(manageRow.name());
        return out;
    }
}
