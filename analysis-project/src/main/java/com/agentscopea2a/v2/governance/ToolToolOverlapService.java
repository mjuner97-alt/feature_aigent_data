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
import com.agentscopea2a.v2.toolrouting.ToolRoutingScanCandidate;
import com.agentscopea2a.v2.toolrouting.ToolRoutingScanService;
import com.agentscopea2a.v2.toolrouting.UnifiedToolMetadataService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool-Tool 功能重复检测 (docs/history/tool-tool-overlap-and-startup-guard-plan.md §3.1, v2 四层一致性规则)。
 *
 * <p>判定哲学: Agent 下钻链路 (skill → tool_index 候选集 → 叶子选型 → router_tool) 中,
 * 两工具只有可能出现在 <b>同一候选集</b> (topic_tags 交集非空, T0 前提) 里被二选一。
 * v2 判定主轴是<b>四层一致性</b>: 业务主题 (L1 topic_tags) / 指标标签 (L2 metric_tags) /
 * 维度标签 (L3 dimension_tags, 均按集合相等比较) 三层外加描述 (L4, cosine ≥ cosineHighThreshold
 * 视为无差异), <b>任一层有不同即判断两个工具不一致</b>; 四层全部无差异才构成重复 (HIGH)。
 * 名称相似 (T3) 只是命名卫生信号, 单独不构成重复。
 * 参数签名 (T2) 退居两用: degraded (embedding 不可用) 时作为唯一硬证据
 * (L1-L3 相等 + 签名完全一致仍判 HIGH), 非 degraded 时仅作展示信号。
 *
 * <p>分级:
 * <ul>
 * <li>HIGH   = T0 且 L1∧L2∧L3∧L4 全部无差异; degraded 下 L4 无证据时: L1-L3 相等 且 T2 签名完全一致</li>
 * <li>MEDIUM = T0 且 (cosine ≥ cosineThreshold (0.80) 或 T2 命中), 但存在差异层 → 封顶 MEDIUM 并点名差异层</li>
 * <li>LOW    = 仅 T3 命中 (命名卫生), 或 T0 交集 ≥ 2 但 T1/T2 均未命中 (巡检)</li>
 * </ul>
 *
 * <p>结果为派生数据, 进程内 TTL 缓存; 工具路由元数据保存后主动失效。重叠不落库。
 * 检测范围含"已配置未启用"工具 (配置期预警, 至少一侧已启用才配对);
 * 同 toolId 跨类型重复注册 (DUPLICATE_TOOL_ID) 的对不经四层证据直接判 HIGH
 * (调用层按 toolId 寻址, 本身就不可区分); 启用拦截与启动审计只取双方都已启用的对。
 */
public class ToolToolOverlapService {

    private static final double SIGNATURE_JACCARD_THRESHOLD = 0.8;

    private final ToolRoutingMetadataRepository toolRoutingMetadataRepository;
    private final GovernanceEmbeddingCache embeddingCache;
    private final UnifiedToolMetadataService unifiedToolMetadataService;
    private final ToolRoutingScanService scanService;

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
            ToolRoutingScanService scanService,
            double cosineThreshold,
            double cosineHighThreshold,
            double nameSimilarityThreshold,
            long cacheTtlMillis) {
        this.toolRoutingMetadataRepository = toolRoutingMetadataRepository;
        this.embeddingCache = embeddingCache;
        this.unifiedToolMetadataService = unifiedToolMetadataService;
        this.scanService = scanService;
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
            Map<String, Long> highByTool,
            Map<String, Long> duplicateByTool) {

        public long high() {
            return counts.getOrDefault("HIGH", 0L);
        }
    }

    public OverlapSummary summary() {
        OverlapReport report = report();
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> highByTool = new HashMap<>();
        Map<String, Long> duplicateByTool = new HashMap<>();
        for (ToolToolOverlapView item : report.items()) {
            counts.merge(item.level(), 1L, Long::sum);
            if (!"HIGH".equals(item.level())) {
                continue;
            }
            if (item.toolIdA().equals(item.toolIdB())) {
                // 同 toolId 跨类型注册冲突: 不进 highByTool (前端不据此置灰启用开关——
                // 元数据一行可指向其中一个类型, 启用无选型风险), 单独给角标信号
                duplicateByTool.merge(item.toolIdA(), 1L, Long::sum);
            } else {
                highByTool.merge(item.toolIdA(), 1L, Long::sum);
                highByTool.merge(item.toolIdB(), 1L, Long::sum);
            }
        }
        return new OverlapSummary(report.degraded(), counts, highByTool, duplicateByTool);
    }

    private List<ToolToolOverlapView> compute(boolean degraded) {
        List<ToolRoutingMetadata> tools = scanScope();

        // T1 向量: 非 degraded 时逐工具取向量一次, O(n) 次 embed (命中缓存)。
        Map<String, float[]> vectors = new HashMap<>();
        if (!degraded) {
            for (ToolRoutingMetadata tool : tools) {
                vectors.put(scanKey(tool.toolId(), tool.toolType()), embeddingCache.embeddingFor(
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

    /**
     * 检测范围 = 已启用 + 已配置未启用 (启用侧优先, 同 toolId 去重)。
     * 未启用工具不在路由目录, 不构成 Agent 瞎选风险, 但配置期就该看到与已启用工具的
     * 重叠隐患 (启用前整改); 双方都未启用的对是纯噪音, 不参与比较。
     */
    private List<ToolRoutingMetadata> scanScope() {
        Map<String, ToolRoutingMetadata> byId = new LinkedHashMap<>();
        for (ToolRoutingMetadata tool : toolRoutingMetadataRepository.findEnabled()) {
            byId.put(scanKey(tool.toolId(), tool.toolType()), tool);
        }
        for (ToolRoutingMetadata tool : toolRoutingMetadataRepository.findAll()) {
            if (!tool.enabled()) {
                byId.putIfAbsent(scanKey(tool.toolId(), tool.toolType()), tool);
            }
        }
        // 注册表跨类型同 toolId 冲突 (DUPLICATE_TOOL_ID): 元数据表主键是 tool_id, 同 toolId 的
        // 另一类型注册在元数据表里物理上不存在, 从扫描候选合成记录补进范围参与配对。只补冲突 id,
        // 不引入全量注册工具 (避免配对数与 embed 量爆炸); 合成记录无标签, 不会与其他工具配对,
        // 仅在同 toolId 分支生效; 注册表条目是活性注册, 按已启用处理。
        if (scanService != null) {
            for (ToolRoutingScanCandidate candidate : scanService.scan()) {
                if (!candidate.issueCodes().contains("DUPLICATE_TOOL_ID")) {
                    continue;
                }
                byId.putIfAbsent(scanKey(candidate.toolId(), candidate.toolType()),
                        new ToolRoutingMetadata(candidate.toolId(), candidate.toolType(),
                                candidate.description(), List.of(), List.of(), List.of(), 0, true, null));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** 去重键 = toolId + 类型: 同 toolId 跨类型重复注册 (DUPLICATE_TOOL_ID) 的记录都要保留参与配对。 */
    private static String scanKey(String toolId, com.agentscopea2a.v2.toolrouting.ToolRoutingToolType toolType) {
        return toolId + "|" + toolType;
    }

    private ToolToolOverlapView evaluatePair(
            ToolRoutingMetadata a, ToolRoutingMetadata b,
            Map<String, float[]> vectors, Map<String, Signature> signatures, boolean degraded) {
        // 双方都未启用的对不参与比较 (都不在路由目录, 无选型风险)
        if (!a.enabled() && !b.enabled()) {
            return null;
        }
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

        // 同 toolId 跨类型重复注册 (扫描问题 DUPLICATE_TOOL_ID): Agent 调用层按 toolId 寻址,
        // 本身就无法区分, 不依赖四层证据直接判 HIGH; 双方均未启用的对已在上方排除。
        if (a.toolId().equals(b.toolId())) {
            String topics = String.join("、", topicOverlap);
            String suggestion = "工具 " + a.toolId() + " 以 " + a.toolType() + " 与 " + b.toolType()
                    + " 两种类型重复注册（" + (topics.isEmpty() ? "无共同主题标签" : topics) + "），"
                    + "Agent 调用层无法区分，请重命名独立 toolId 或退役其中一个";
            return new ToolToolOverlapView(
                    a.toolId(), a.toolType().name(),
                    b.toolId(), b.toolType().name(),
                    "HIGH",
                    List.copyOf(topicOverlap),
                    0,
                    false,
                    false,
                    List.of(),
                    List.copyOf(a.metricTags()),
                    List.copyOf(b.metricTags()),
                    List.copyOf(a.dimensionTags()),
                    List.copyOf(b.dimensionTags()),
                    suggestion,
                    a.enabled(),
                    b.enabled());
        }

        boolean cosineKnown = false;
        double cosine = 0;
        if (!degraded) {
            float[] va = vectors.get(scanKey(a.toolId(), a.toolType()));
            float[] vb = vectors.get(scanKey(b.toolId(), b.toolType()));
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

        // v2 四层一致性: L1 topic / L2 metric / L3 dimension (集合相等) + L4 描述 (cosine >= 0.88)。
        // 任一层"有不同"即差异层; 四层全部无差异才 HIGH。集合相等而非"不相交"豁免:
        // 生产变体工具的 dimension_tags 相交 (都含"版本计划") 但不同, 必须判差异。
        List<String> differingLayers = new ArrayList<>();
        if (!new HashSet<>(a.topicTags()).equals(new HashSet<>(b.topicTags()))) {
            differingLayers.add("业务主题");
        }
        if (!new HashSet<>(a.metricTags()).equals(new HashSet<>(b.metricTags()))) {
            differingLayers.add("指标标签");
        }
        if (!new HashSet<>(a.dimensionTags()).equals(new HashSet<>(b.dimensionTags()))) {
            differingLayers.add("维度标签");
        }
        // degraded 时 L4 无证据, 不单独构成差异 (§3.3), 由签名硬证据兜底
        boolean descriptionDiffers = cosineKnown && cosine < cosineHighThreshold;
        if (descriptionDiffers) {
            differingLayers.add("描述");
        }

        boolean fourLayersSame = !topicOverlap.isEmpty() && differingLayers.isEmpty();
        boolean highEvidence = fourLayersSame && (cosineKnown || signatureSame);

        String level = null;
        if (highEvidence) {
            level = "HIGH";
        } else if (!topicOverlap.isEmpty()
                && ((cosineKnown && cosine >= cosineThreshold) || signatureSame)) {
            level = "MEDIUM";
        } else if (aliasHit || topicOverlap.size() >= 2) {
            level = "LOW";
        }
        if (level == null) {
            return null;
        }

        String topics = String.join("、", topicOverlap);
        String suggestion;
        if ("HIGH".equals(level) && cosineKnown) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 在同一候选集（" + topics + "）内描述无法区分（cosine="
                    + String.format(Locale.ROOT, "%.2f", cosine) + "），Agent 叶子选型必然瞎选，请处理后重启";
        } else if ("HIGH".equals(level)) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 在同一候选集（" + topics + "）内参数签名完全一致（embedding 不可用，仅签名判定），请处理后重启";
        } else if ("MEDIUM".equals(level)) {
            suggestion = "工具 " + a.toolId() + " 与工具 " + b.toolId()
                    + " 同候选集（" + topics + "）内"
                    + (cosineKnown && !descriptionDiffers
                            ? "描述无法区分（cosine=" + String.format(Locale.ROOT, "%.2f", cosine) + "）但"
                            : "")
                    + layerDiffText(differingLayers, a, b, cosineKnown, cosine)
                    + "，Agent 仍可按差异选型，请人工确认是否重复";
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
                List.copyOf(differingLayers),
                List.copyOf(a.metricTags()),
                List.copyOf(b.metricTags()),
                List.copyOf(a.dimensionTags()),
                List.copyOf(b.dimensionTags()),
                suggestion,
                a.enabled(),
                b.enabled());
    }

    /** 差异层明细文案, 如 "维度标签不同（A: 应用、版本计划；B: 部门、版本计划）、描述相近但有差异（cosine=0.85）"。 */
    private String layerDiffText(List<String> differingLayers, ToolRoutingMetadata a, ToolRoutingMetadata b,
                                 boolean cosineKnown, double cosine) {
        List<String> parts = new ArrayList<>();
        for (String layer : differingLayers) {
            switch (layer) {
                case "业务主题" -> parts.add("业务主题不同（A: " + String.join("、", a.topicTags())
                        + "；B: " + String.join("、", b.topicTags()) + "）");
                case "指标标签" -> parts.add("指标标签不同（A: " + String.join("、", a.metricTags())
                        + "；B: " + String.join("、", b.metricTags()) + "）");
                case "维度标签" -> parts.add("维度标签不同（A: " + String.join("、", a.dimensionTags())
                        + "；B: " + String.join("、", b.dimensionTags()) + "）");
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
