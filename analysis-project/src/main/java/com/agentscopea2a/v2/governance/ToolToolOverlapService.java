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

import com.agentscopea2a.v2.toolrouting.ToolMetadataResponse;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import com.agentscopea2a.v2.toolrouting.UnifiedToolMetadataService;

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
 * Tool-Tool 功能重复检测 (docs/history/tool-tool-overlap-and-startup-guard-plan.md §3.1)。
 *
 * <p>判定哲学: Agent 下钻链路 (skill → tool_index 候选集 → 叶子选型 → router_tool) 中,
 * 两工具只有可能出现在 <b>同一候选集</b> (topic_tags 交集非空, T0 前提) 里被二选一;
 * 到叶子层后若 Agent 无法从描述上 (T1 cosine) 或功能上 (T2 参数签名) 区分二者, 即为重复。
 * 名称相似 (T3) 只是命名卫生信号, 单独不构成重复。
 *
 * <p>分级:
 * <ul>
 * <li>HIGH   = T0 且 (T1 ≥ cosineHighThreshold (0.88), 或 T1 ≥ cosineThreshold (0.80) 且 T2 命中,
 *             或 degraded 下仅 T0+T2 完全一致 —— 签名是硬证据, 不依赖 embedding)</li>
 * <li>MEDIUM = T0 且 T1 ≥ cosineThreshold, 但功能签名可区分</li>
 * <li>LOW    = 仅 T3 命中 (命名卫生), 或 T0 交集 ≥ 2 但 T1/T2 均未命中 (巡检)</li>
 * </ul>
 *
 * <p>结果为派生数据, 进程内 TTL 缓存; 工具路由元数据保存后主动失效。重叠不落库。
 */
public class ToolToolOverlapService {

    private static final double SIGNATURE_JACCARD_THRESHOLD = 0.8;

    private final ToolRoutingMetadataRepository toolRoutingMetadataRepository;
    private final GovernanceEmbeddingCache embeddingCache;
    private final UnifiedToolMetadataService unifiedToolMetadataService;

    private final double cosineThreshold;
    private final double cosineHighThreshold;
    private final double nameSimilarityThreshold;

    private final long cacheTtlMillis;
    private volatile List<ToolToolOverlapView> cached;
    private volatile boolean cachedDegraded;
    private volatile long cachedAt;

    public ToolToolOverlapService(
            ToolRoutingMetadataRepository toolRoutingMetadataRepository,
            GovernanceEmbeddingCache embeddingCache,
            UnifiedToolMetadataService unifiedToolMetadataService,
            double cosineThreshold,
            double cosineHighThreshold,
            double nameSimilarityThreshold,
            long cacheTtlMillis) {
        this.toolRoutingMetadataRepository = toolRoutingMetadataRepository;
        this.embeddingCache = embeddingCache;
        this.unifiedToolMetadataService = unifiedToolMetadataService;
        this.cosineThreshold = cosineThreshold;
        this.cosineHighThreshold = cosineHighThreshold;
        this.nameSimilarityThreshold = nameSimilarityThreshold;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 工具路由元数据保存成功后调用, 让管理页修改立即生效。 */
    public void invalidate() {
        cached = null;
    }

    public record OverlapReport(boolean degraded, List<ToolToolOverlapView> items) {}

    public synchronized OverlapReport report() {
        List<ToolToolOverlapView> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < cacheTtlMillis) {
            return new OverlapReport(cachedDegraded, snapshot);
        }
        // 不等全局 warm(): 启动审计先于后台预热完成, 只要有 provider 就逐对尝试取向量,
        // 向量缺失的对自然降级为纯文本信号 (cosineKnown=false)。
        boolean degraded = embeddingCache == null || !embeddingCache.semanticAvailable();
        List<ToolToolOverlapView> items = compute(degraded);
        cached = items;
        cachedDegraded = degraded;
        cachedAt = System.currentTimeMillis();
        return new OverlapReport(degraded, items);
    }

    /**
     * 同步预热工具描述向量 (仅 block-tool-overlap=true 的启动审计调用, 避免启动期
     * 被后台预热未完成拖成 degraded 而漏判 cosine 证据)。命中缓存无 IO, 未命中逐条 embed。
     */
    public void ensureToolVectorsWarmed() {
        if (embeddingCache == null || !embeddingCache.semanticAvailable()) {
            return;
        }
        for (ToolRoutingMetadata tool : toolRoutingMetadataRepository.findEnabled()) {
            embeddingCache.embeddingFor(
                    GovernanceEmbeddingCache.EntityType.TOOL, tool.toolId(), tool.description());
        }
    }

    public record OverlapSummary(
            boolean degraded,
            Map<String, Long> counts,
            Map<String, Long> highByTool) {

        public long high() {
            return counts.getOrDefault("HIGH", 0L);
        }
    }

    public OverlapSummary summary() {
        OverlapReport report = report();
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> highByTool = new HashMap<>();
        for (ToolToolOverlapView item : report.items()) {
            counts.merge(item.level(), 1L, Long::sum);
            if ("HIGH".equals(item.level())) {
                highByTool.merge(item.toolIdA(), 1L, Long::sum);
                highByTool.merge(item.toolIdB(), 1L, Long::sum);
            }
        }
        return new OverlapSummary(report.degraded(), counts, highByTool);
    }

    private List<ToolToolOverlapView> compute(boolean degraded) {
        List<ToolRoutingMetadata> tools = toolRoutingMetadataRepository.findEnabled();

        // T1 向量: 非 degraded 时逐工具取向量一次, O(n) 次 embed (命中缓存)。
        Map<String, float[]> vectors = new HashMap<>();
        if (!degraded) {
            for (ToolRoutingMetadata tool : tools) {
                vectors.put(tool.toolId(), embeddingCache.embeddingFor(
                        GovernanceEmbeddingCache.EntityType.TOOL, tool.toolId(), tool.description()));
            }
        }

        // T2 参数签名: 只对进入候选集比较的工具惰性取一次 (内部走注册表/反射, 有 IO)。
        Map<String, Signature> signatures = new HashMap<>();

        List<ToolToolOverlapView> result = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            for (int j = i + 1; j < tools.size(); j++) {
                ToolToolOverlapView view = evaluatePair(tools.get(i), tools.get(j),
                        vectors, signatures, degraded);
                if (view != null) {
                    result.add(view);
                }
            }
        }
        result.sort(Comparator.comparing(ToolToolOverlapView::level)
                .thenComparing(ToolToolOverlapView::toolIdA)
                .thenComparing(ToolToolOverlapView::toolIdB));
        return List.copyOf(result);
    }

    private ToolToolOverlapView evaluatePair(
            ToolRoutingMetadata a, ToolRoutingMetadata b,
            Map<String, float[]> vectors, Map<String, Signature> signatures, boolean degraded) {
        // T3 名称相似 (与 T0 无关, 单独命中只给 LOW 命名卫生)
        String normalizedA = TextSimilarityUtil.normalizeName(a.toolId());
        String normalizedB = TextSimilarityUtil.normalizeName(b.toolId());
        boolean aliasHit = !normalizedA.isEmpty() && !normalizedB.isEmpty()
                && TextSimilarityUtil.levenshteinSimilarity(normalizedA, normalizedB)
                        >= nameSimilarityThreshold;

        // T0 候选集前提
        List<String> topicOverlap = a.topicTags().stream()
                .filter(new HashSet<>(b.topicTags())::contains)
                .distinct()
                .toList();

        boolean cosineKnown = false;
        double cosine = 0;
        if (!degraded) {
            float[] va = vectors.get(a.toolId());
            float[] vb = vectors.get(b.toolId());
            if (va != null && vb != null) {
                cosine = TextSimilarityUtil.cosine(va, vb);
                cosineKnown = true;
            }
        }

        Signature sigA = null;
        Signature sigB = null;
        boolean signatureSame = false;
        if (!topicOverlap.isEmpty()) {
            sigA = signature(a, signatures);
            sigB = signature(b, signatures);
            signatureSame = sigA != null && sigB != null && sigA.sameAs(sigB);
        }

        String level = null;
        if (!topicOverlap.isEmpty() && cosineKnown && cosine >= cosineHighThreshold) {
            level = "HIGH";
        } else if (!topicOverlap.isEmpty() && signatureSame
                && (!cosineKnown || cosine >= cosineThreshold)) {
            level = "HIGH";
        } else if (!topicOverlap.isEmpty() && cosineKnown && cosine >= cosineThreshold) {
            level = "MEDIUM";
        } else if (aliasHit || topicOverlap.size() >= 2) {
            level = "LOW";
        }
        if (level == null) {
            return null;
        }

        String topics = String.join("、", topicOverlap);
        String suggestion;
        if ("HIGH".equals(level) && cosineKnown && cosine >= cosineHighThreshold) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 在同一候选集（" + topics + "）内描述无法区分（cosine="
                    + String.format(Locale.ROOT, "%.2f", cosine) + "），Agent 叶子选型必然瞎选，请处理后重启";
        } else if ("HIGH".equals(level)) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 在同一候选集（" + topics + "）内"
                    + (cosineKnown
                            ? "描述与参数签名均无法区分（cosine=" + String.format(Locale.ROOT, "%.2f", cosine) + "）"
                            : "参数签名完全一致（embedding 不可用，仅签名判定）")
                    + "，请处理后重启";
        } else if ("MEDIUM".equals(level)) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 同候选集（" + topics + "）内描述相近（cosine="
                    + String.format(Locale.ROOT, "%.2f", cosine) + "）但功能签名可区分，请人工确认是否重复";
        } else if (aliasHit) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " toolId 名称高度相似（命名卫生），请确认是否迭代版本或重复注册";
        } else {
            suggestion = "业务主题相关（" + topics + "），供巡检参考";
        }

        return new ToolToolOverlapView(
                a.toolId(), a.toolType().name(),
                b.toolId(), b.toolType().name(),
                level,
                List.copyOf(topicOverlap),
                cosine,
                signatureSame,
                aliasHit,
                suggestion);
    }

    /** 取工具参数签名, 失败 (工具不可用/注册表缺失) 返回 null, 该工具不参与 T2 判定。 */
    private Signature signature(ToolRoutingMetadata tool, Map<String, Signature> cache) {
        if (unifiedToolMetadataService == null) {
            return null;
        }
        return cache.computeIfAbsent(tool.toolId(), id -> {
            try {
                ToolMetadataResponse response = unifiedToolMetadataService.find(id);
                return Signature.of(response.parameters());
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** 参数功能签名: 全参数名集合 + 必填参数名集合。空参数表视为无签名 (不参与 T2)。 */
    private record Signature(Set<String> allNames, Set<String> requiredNames) {

        static Signature of(List<com.agentscopea2a.v2.toolrouting.ToolParameterMetadata> parameters) {
            if (parameters == null || parameters.isEmpty()) {
                return null;
            }
            Set<String> all = new LinkedHashSet<>();
            Set<String> required = new LinkedHashSet<>();
            for (var parameter : parameters) {
                all.add(parameter.name());
                if (parameter.required()) {
                    required.add(parameter.name());
                }
            }
            return new Signature(all, required);
        }

        boolean sameAs(Signature other) {
            return jaccard(allNames, other.allNames) >= SIGNATURE_JACCARD_THRESHOLD
                    && requiredNames.equals(other.requiredNames);
        }

        private static double jaccard(Set<String> a, Set<String> b) {
            Set<String> intersection = new HashSet<>(a);
            intersection.retainAll(b);
            Set<String> union = new HashSet<>(a);
            union.addAll(b);
            return union.isEmpty() ? 0 : ((double) intersection.size()) / union.size();
        }
    }
}
