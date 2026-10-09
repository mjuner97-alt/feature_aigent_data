package com.agentscopea2a.v2.toolrouting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * 不可区分, 见 ToolToolOverlapService) 单独走 {@code block-tool-overlap} 开关阻断,
 * 不依赖 strict-startup; 提示语固定为"与工具 xxx 有重复，请处理"。
 * 防雪崩: 重复工具数超过 block-tool-overlap-max-tools (默认 4) 时视为批量数据问题,
 * 降级为告警不阻断启动 (拦下来只会让整个服务对所有人不可用)。
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
        for (ToolRoutingMetadata metadata : metadataRepository.findAll()) {
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
            // HIGH 对两侧各记一条, detail 即用户可见提示语
            for (com.agentscopea2a.v2.governance.ToolToolOverlapView pair
                    : toolToolOverlapService.report().items()) {
                if (!"HIGH".equals(pair.level())) {
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

    private static void addIssue(List<AuditIssue> issues, String kind, String toolId, String detail) {
        issues.add(new AuditIssue(kind, toolId, detail));
    }

    public record AuditIssue(String kind, String toolId, String detail) { }
}
