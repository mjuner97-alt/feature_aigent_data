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

import com.agentscopea2a.v2.skills.SkillRoutingMetadata;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 广场创建/保存时的描述相似检测 (方案 §2)。
 *
 * <p>两级算法 (任一命中即进入相似列表):
 * <ul>
 * <li>L1 文本: 名称归一化后与已有 name / retrieval_name / 路由 keywords 精确或
 *     Levenshtein ≥ nameLevenshteinThreshold 近匹配; 描述 bigram Jaccard ≥ jaccardThreshold</li>
 * <li>L2 语义: 描述 embedding cosine ≥ cosineThreshold (无 provider / 预热未完成时降级)</li>
 * </ul>
 *
 * <p>比较范围是全库未删除 Skill (含他人 PRIVATE, 有意取舍见方案 §2.4)。
 */
public class SkillDescriptionSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(SkillDescriptionSimilarityService.class);

    /** 新草稿描述的 embedding 缓存键 (非持久实体, 只为复用 GovernanceEmbeddingCache)。 */
    private static final String DRAFT_ENTITY_ID = "_similarity_draft_";

    private static final int MAX_MATCHES = 5;

    private final boolean enabled;
    private final SkillDescriptionSource skillDescriptionSource;
    private final SkillRoutingMetadataRepository skillRoutingMetadataRepository;
    private final GovernanceEmbeddingCache embeddingCache;

    private final double cosineThreshold;
    private final double nameLevenshteinThreshold;
    private final double jaccardThreshold;

    public SkillDescriptionSimilarityService(
            boolean enabled,
            SkillDescriptionSource skillDescriptionSource,
            SkillRoutingMetadataRepository skillRoutingMetadataRepository,
            GovernanceEmbeddingCache embeddingCache,
            double cosineThreshold,
            double nameLevenshteinThreshold,
            double jaccardThreshold) {
        this.enabled = enabled;
        this.skillDescriptionSource = skillDescriptionSource;
        this.skillRoutingMetadataRepository = skillRoutingMetadataRepository;
        this.embeddingCache = embeddingCache;
        this.cosineThreshold = cosineThreshold;
        this.nameLevenshteinThreshold = nameLevenshteinThreshold;
        this.jaccardThreshold = jaccardThreshold;
    }

    public SkillSimilarityCheckResult check(String candidateName, String candidateDescription,
                                             Long excludeSkillId) {
        if (!enabled) {
            return new SkillSimilarityCheckResult(false, List.of());
        }
        boolean degraded = !embeddingCache.semanticAvailable() || !embeddingCache.warm();
        List<SkillDescriptionSource.SkillDescriptionRow> rows = skillDescriptionSource.allActiveSkills();
        if (rows.isEmpty()) {
            return new SkillSimilarityCheckResult(degraded, List.of());
        }
        Map<String, List<String>> routingKeywords = routingKeywordsByRetrievalName();

        float[] candidateVector = null;
        if (!degraded && candidateDescription != null && !candidateDescription.isBlank()) {
            candidateVector = embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.SKILL, DRAFT_ENTITY_ID, candidateDescription);
        }

        List<SkillSimilarityCheckResult.SkillSimilarityMatch> matches = new ArrayList<>();
        for (SkillDescriptionSource.SkillDescriptionRow row : rows) {
            if (excludeSkillId != null && excludeSkillId.equals(row.id())) {
                continue;
            }
            MatchEvidence evidence = evaluate(
                    candidateName, candidateDescription, candidateVector, row,
                    routingKeywords.getOrDefault(row.retrievalName(), List.of()), degraded);
            if (evidence != null) {
                matches.add(new SkillSimilarityCheckResult.SkillSimilarityMatch(
                        row.id(), row.name(), row.description(), row.ownerUserId(), row.visibility(),
                        evidence.similarity, evidence.details));
            }
        }
        matches.sort(Comparator.comparingDouble(
                        (SkillSimilarityCheckResult.SkillSimilarityMatch m) -> m.similarity())
                .reversed());
        return new SkillSimilarityCheckResult(degraded,
                matches.stream().limit(MAX_MATCHES).toList());
    }

    private record MatchEvidence(double similarity, List<String> details) {}

    private MatchEvidence evaluate(
            String candidateName,
            String candidateDescription,
            float[] candidateVector,
            SkillDescriptionSource.SkillDescriptionRow row,
            List<String> keywords,
            boolean degraded) {
        List<String> details = new ArrayList<>();
        double similarity = 0;

        // L1 名称: 新名称 vs 已有 name / retrieval_name / 路由 keywords
        if (candidateName != null && !candidateName.isBlank()) {
            String normalizedCandidate = TextSimilarityUtil.normalizeName(candidateName);
            double bestNameScore = 0;
            String bestAgainst = null;
            for (String target : nameTargets(row, keywords)) {
                if (target == null || target.isBlank()) continue;
                String normalizedTarget = TextSimilarityUtil.normalizeName(target);
                double score = normalizedCandidate.equals(normalizedTarget)
                        ? 1.0
                        : TextSimilarityUtil.levenshteinSimilarity(normalizedCandidate, normalizedTarget);
                if (score > bestNameScore) {
                    bestNameScore = score;
                    bestAgainst = target;
                }
            }
            if (bestNameScore >= nameLevenshteinThreshold) {
                details.add(String.format("名称与「%s」相近 (%.2f)", bestAgainst, bestNameScore));
                similarity = Math.max(similarity, bestNameScore);
            }
        }

        // L1 描述: bigram Jaccard
        if (candidateDescription != null && !candidateDescription.isBlank()
                && row.description() != null && !row.description().isBlank()) {
            double jaccard = TextSimilarityUtil.bigramJaccard(candidateDescription, row.description());
            if (jaccard >= jaccardThreshold) {
                details.add(String.format("描述文本相似 (%.2f)", jaccard));
                similarity = Math.max(similarity, jaccard);
            }
        }

        // L2 语义: cosine
        if (!degraded && candidateVector != null
                && row.description() != null && !row.description().isBlank()) {
            float[] rowVector = embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.SKILL, row.retrievalName(), row.description());
            double cosine = TextSimilarityUtil.cosine(candidateVector, rowVector);
            if (cosine >= cosineThreshold) {
                details.add(String.format("语义相似 (%.2f)", cosine));
                similarity = Math.max(similarity, cosine);
            }
        }

        if (details.isEmpty()) {
            return null;
        }
        return new MatchEvidence(similarity, List.copyOf(details));
    }

    private static List<String> nameTargets(
            SkillDescriptionSource.SkillDescriptionRow row, List<String> keywords) {
        List<String> targets = new ArrayList<>();
        targets.add(row.name());
        targets.add(row.retrievalName());
        targets.addAll(keywords);
        return targets;
    }

    /** 一次取全部路由元数据建 keywords 映射, 避免 N+1 查询。 */
    private Map<String, List<String>> routingKeywordsByRetrievalName() {
        try {
            return skillRoutingMetadataRepository.findAll().stream()
                    .collect(Collectors.toMap(
                            SkillRoutingMetadata::skillName,
                            m -> m.keywords() == null ? List.of() : m.keywords(),
                            (a, b) -> a));
        } catch (Exception e) {
            log.warn("routingKeywordsByRetrievalName failed: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
