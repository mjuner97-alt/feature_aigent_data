package com.agentscopea2a.v2.toolrouting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup consistency audit for unified tool routing (plan §8.2). Runs once after all tool
 * registrations are complete; with {@code strict-startup=true} the audit blocks startup when
 * route metadata and the three authoritative registries disagree.
 *
 * <p>Blocking categories: missing route metadata for an enabled executable object, orphan
 * metadata (enabled route pointing to a missing or disabled object), duplicate tool IDs
 * across registries, invalid topic/metric tags on enabled metadata, and SCRIPT entries whose
 * source file is unavailable.
 *
 * <p>Tool-Tool 重复 (block-tool-overlap=true): HIGH 级重叠对 (同一候选集内描述/功能签名
 * 不可区分, 见 ToolToolOverlapService) 执行<b>"仅可启用其中一个"</b>策略——双方已启用的对
 * 在启动期自动停用低优先级一侧 (优先级相同保留 toolId 较小者) 并告警, 不阻断启动;
 * 重复工具数超过 block-tool-overlap-max-tools (默认 4) 时视为批量数据问题, 不自动变更
 * 元数据, 降级为告警。同 toolId 跨类型注册冲突只能改注册表整改, 只告警不变更。
 */
public class ToolRoutingStartupAudit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutingStartupAudit.class);

    /** block-tool-overlap 允许阻断启动的最大重复工具数, 超过则降级为告警。 */
    static final int DEFAULT_MAX_BLOCKING_OVERLAP_TOOLS = 4;

    private final ToolRoutingScanService scanService;
    private final ToolRoutingMetadataRepository metadataRepository;
    private final ToolRoutingMetrics metrics;
    private final boolean strictStartup;
    private final com.agentscopea2a.v2.governance.ToolToolOverlapService toolToolOverlapService;
    private final boolean blockToolOverlap;
    private final int blockToolOverlapMaxTools;

    public ToolRoutingStartupAudit(ToolRoutingScanService scanService,
                                   ToolRoutingMetadataRepository metadataRepository,
                                   ToolRoutingMetrics metrics,
                                   boolean strictStartup) {
        this(scanService, metadataRepository, metrics, strictStartup, null, false,
                DEFAULT_MAX_BLOCKING_OVERLAP_TOOLS);
    }

    public ToolRoutingStartupAudit(ToolRoutingScanService scanService,
                                   ToolRoutingMetadataRepository metadataRepository,
                                   ToolRoutingMetrics metrics,
                                   boolean strictStartup,
                                   com.agentscopea2a.v2.governance.ToolToolOverlapService toolToolOverlapService,
                                   boolean blockToolOverlap) {
        this(scanService, metadataRepository, metrics, strictStartup, toolToolOverlapService,
                blockToolOverlap, DEFAULT_MAX_BLOCKING_OVERLAP_TOOLS);
    }

    public ToolRoutingStartupAudit(ToolRoutingScanService scanService,
                                   ToolRoutingMetadataRepository metadataRepository,
                                   ToolRoutingMetrics metrics,
                                   boolean strictStartup,
                                   com.agentscopea2a.v2.governance.ToolToolOverlapService toolToolOverlapService,
                                   boolean blockToolOverlap,
                                   int blockToolOverlapMaxTools) {
        this.scanService = scanService;
        this.metadataRepository = metadataRepository;
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
        this.strictStartup = strictStartup;
        this.toolToolOverlapService = toolToolOverlapService;
        this.blockToolOverlap = blockToolOverlap;
        this.blockToolOverlapMaxTools = blockToolOverlapMaxTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AuditIssue> issues = audit();
        if (issues.isEmpty()) {
            log.info("ToolRoutingStartupAudit: no consistency issues (strict={}, block-tool-overlap={})",
                    strictStartup, blockToolOverlap);
            return;
        }
        for (AuditIssue issue : issues) {
            log.warn("ToolRoutingStartupAudit: [{}] {} ({})", issue.kind(), issue.detail(), issue.toolId());
            metrics.consistencyError(issue.kind());
        }
        if (strictStartup) {
            throw new IllegalStateException("Tool routing startup audit found " + issues.size()
                    + " consistency issue(s); strict-startup=true blocks startup. First: " + issues.get(0));
        }
        List<AuditIssue> overlaps = issues.stream()
                .filter(issue -> "tool_overlap".equals(issue.kind()))
                .toList();
        if (blockToolOverlap && !overlaps.isEmpty()) {
            long affectedTools = overlaps.stream().map(AuditIssue::toolId).distinct().count();
            if (affectedTools <= blockToolOverlapMaxTools) {
                throw new IllegalStateException("工具路由启动检测到 " + overlaps.size()
                        + " 组工具功能重复，block-tool-overlap=true 禁止启动。明细: " + overlaps);
            }
            log.warn("ToolRoutingStartupAudit: {} 组工具功能重复涉及 {} 个工具, 超过 "
                            + "block-tool-overlap-max-tools={} 阈值, 视为批量数据问题, 降级为告警不阻断启动",
                    overlaps.size(), affectedTools, blockToolOverlapMaxTools);
        }
        log.warn("ToolRoutingStartupAudit: {} issue(s) recorded; strict-startup={}, block-tool-overlap={}, continuing with bad records excluded from the catalog",
                issues.size(), strictStartup, blockToolOverlap);
    }

    public List<AuditIssue> audit() {
        List<AuditIssue> issues = new ArrayList<>();
        List<ToolRoutingScanCandidate> scan = scanService.scan();
        Set<String> executableIds = new HashSet<>();
        for (ToolRoutingScanCandidate candidate : scan) {
            executableIds.add(candidate.toolId());
            if (!candidate.configured()) {
                addIssue(issues, "missing", candidate.toolId(),
                        "enabled " + candidate.toolType() + " object has no route metadata");
            }
            for (String issue : candidate.issueCodes()) {
                switch (issue) {
                    case "DUPLICATE_TOOL_ID" -> addIssue(issues, "type_mismatch", candidate.toolId(),
                            "tool id shared by multiple registries");
                    case "SCRIPT_SOURCE_UNAVAILABLE" -> {
                        addIssue(issues, "script_unavailable", candidate.toolId(),
                                "script source file missing or path invalid");
                        metrics.scriptUnavailable("missing_file");
                    }
                    default -> { }
                }
            }
        }
        Map<String, ToolRoutingMetadata> metadataByToolId = new HashMap<>();
        for (ToolRoutingMetadata metadata : metadataRepository.findAll()) {
            metadataByToolId.put(metadata.toolId(), metadata);
            if (!metadata.enabled()) {
                continue;
            }
            if (!executableIds.contains(metadata.toolId())) {
                addIssue(issues, "orphan", metadata.toolId(),
                        "enabled route metadata points to a missing or disabled " + metadata.toolType() + " object");
            }
            if (metadata.topicTags().isEmpty() || metadata.metricTags().isEmpty()) {
                addIssue(issues, "invalid_tags", metadata.toolId(),
                        "enabled metadata requires at least one topic and one metric tag");
            }
        }
        if (toolToolOverlapService != null) {
            // block 开启时同步预热工具向量: 启动审计先于后台预热完成, 不预热会因
            // 向量缺失漏判 cosine 证据 (工具数量小, 逐条 embed 的启动延迟可接受)
            if (blockToolOverlap) {
                toolToolOverlapService.ensureToolVectorsWarmed();
            }
            List<com.agentscopea2a.v2.governance.ToolToolOverlapView> highPairs =
                    toolToolOverlapService.report().items().stream()
                            .filter(pair -> "HIGH".equals(pair.level()))
                            .toList();
            // 仅可启用其中一个: block 开启且重复规模在阈值内时, 双方已启用的 HIGH 对自动停用
            // 低优先级一侧 (确定性规则, 重启幂等); 超阈值视为批量数据问题不自动变更元数据。
            Set<String> autoDisabled = Set.of();
            if (blockToolOverlap) {
                List<com.agentscopea2a.v2.governance.ToolToolOverlapView> bothEnabled = highPairs.stream()
                        .filter(pair -> pair.enabledA() && pair.enabledB()
                                && !pair.toolIdA().equals(pair.toolIdB()))
                        .toList();
                Set<String> affected = new HashSet<>();
                for (com.agentscopea2a.v2.governance.ToolToolOverlapView pair : bothEnabled) {
                    affected.add(pair.toolIdA());
                    affected.add(pair.toolIdB());
                }
                if (!bothEnabled.isEmpty() && affected.size() <= blockToolOverlapMaxTools) {
                    autoDisabled = autoDisableOverlapLosers(bothEnabled, metadataByToolId);
                }
            }
            // HIGH 对两侧各记一条, detail 即用户可见提示语。
            // 只取双方都已启用且未被自动处置的对: 未启用工具不在路由目录, 无选型风险 (走页面预警)。
            for (com.agentscopea2a.v2.governance.ToolToolOverlapView pair : highPairs) {
                if (!pair.enabledA() || !pair.enabledB()) {
                    continue;
                }
                // 同 toolId 跨类型重复注册: 只能改注册表 (重命名/退役) 整改, 页面无法处置,
                // 拦启动不成比例, 只告警 (重叠页与工具路由页角标照常可见)
                if (pair.toolIdA().equals(pair.toolIdB())) {
                    log.warn("ToolRoutingStartupAudit: [tool_overlap] 工具 {} 以 {} 与 {} 两种类型重复注册, "
                            + "Agent 调用层无法区分, 请重命名独立 toolId 或退役其一 (不阻断启动)",
                            pair.toolIdA(), pair.toolTypeA(), pair.toolTypeB());
                    continue;
                }
                if (autoDisabled.contains(pair.toolIdA()) || autoDisabled.contains(pair.toolIdB())) {
                    continue;
                }
                addIssue(issues, "tool_overlap", pair.toolIdA(),
                        "与工具 " + pair.toolIdB() + " 有重复，请处理");
                addIssue(issues, "tool_overlap", pair.toolIdB(),
                        "与工具 " + pair.toolIdA() + " 有重复，请处理");
            }
        }
        return issues;
    }

    /**
     * "仅可启用其中一个"的启动期自愈: 双方已启用的 HIGH 对, 自动停用低优先级一侧
     * (优先级相同保留 toolId 较小者, 确定性规则保证重启幂等)。返回被停用的 toolId 集合;
     * upsert 失败的对仍按 tool_overlap 记 issue 走原有阻断路径。
     */
    private Set<String> autoDisableOverlapLosers(
            List<com.agentscopea2a.v2.governance.ToolToolOverlapView> pairs,
            Map<String, ToolRoutingMetadata> metadataByToolId) {
        Set<String> disabled = new HashSet<>();
        List<com.agentscopea2a.v2.governance.ToolToolOverlapView> sorted = pairs.stream()
                .sorted(java.util.Comparator.comparing(
                                com.agentscopea2a.v2.governance.ToolToolOverlapView::toolIdA)
                        .thenComparing(com.agentscopea2a.v2.governance.ToolToolOverlapView::toolIdB))
                .toList();
        for (com.agentscopea2a.v2.governance.ToolToolOverlapView pair : sorted) {
            String a = pair.toolIdA();
            String b = pair.toolIdB();
            if (disabled.contains(a) || disabled.contains(b)) {
                continue; // 一侧已因前序对被停用, 该对已满足"至多一个启用"
            }
            String loser = pickLoser(a, b, metadataByToolId);
            ToolRoutingMetadata meta = metadataByToolId.get(loser);
            if (meta == null) {
                continue;
            }
            boolean updated = metadataRepository.upsert(new ToolRoutingMetadata(meta.toolId(), meta.toolType(),
                    meta.description(), meta.topicTags(), meta.metricTags(), meta.dimensionTags(),
                    meta.priority(), false, null));
            if (updated) {
                disabled.add(loser);
                log.warn("ToolRoutingStartupAudit: [tool_overlap] 工具 {} 与 {} 功能重复, "
                                + "自动停用 {} 保留 {} (仅可启用其中一个, 不阻断启动; 可在页面停用保留侧后重新启用被停用侧)",
                        a, b, loser, loser.equals(a) ? b : a);
            } else {
                log.warn("ToolRoutingStartupAudit: [tool_overlap] 工具 {} 自动停用失败, 该重叠对走原有阻断路径", loser);
            }
        }
        if (!disabled.isEmpty()) {
            // 报告基于停用前快照, 失效让页面/审计后续读取反映"一侧已停用"的现状
            toolToolOverlapService.invalidate();
        }
        return disabled;
    }

    private static String pickLoser(String a, String b, Map<String, ToolRoutingMetadata> metadataByToolId) {
        int pa = metadataByToolId.containsKey(a) ? metadataByToolId.get(a).priority() : 0;
        int pb = metadataByToolId.containsKey(b) ? metadataByToolId.get(b).priority() : 0;
        if (pa != pb) {
            return pa > pb ? b : a;
        }
        return a.compareTo(b) <= 0 ? b : a;
    }

    private static void addIssue(List<AuditIssue> issues, String kind, String toolId, String detail) {
        issues.add(new AuditIssue(kind, toolId, detail));
    }

    public record AuditIssue(String kind, String toolId, String detail) { }
}
