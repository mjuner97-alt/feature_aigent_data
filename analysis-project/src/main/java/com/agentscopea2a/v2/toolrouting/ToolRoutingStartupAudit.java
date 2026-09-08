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
 */
public class ToolRoutingStartupAudit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutingStartupAudit.class);

    private final ToolRoutingScanService scanService;
    private final ToolRoutingMetadataRepository metadataRepository;
    private final ToolRoutingMetrics metrics;
    private final boolean strictStartup;

    public ToolRoutingStartupAudit(ToolRoutingScanService scanService,
                                   ToolRoutingMetadataRepository metadataRepository,
                                   ToolRoutingMetrics metrics,
                                   boolean strictStartup) {
        this.scanService = scanService;
        this.metadataRepository = metadataRepository;
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
        this.strictStartup = strictStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AuditIssue> issues = audit();
        if (issues.isEmpty()) {
            log.info("ToolRoutingStartupAudit: no consistency issues (strict={})", strictStartup);
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
        log.warn("ToolRoutingStartupAudit: {} issue(s) recorded; strict-startup=false, continuing with bad records excluded from the catalog",
                issues.size());
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
        return issues;
    }

    private static void addIssue(List<AuditIssue> issues, String kind, String toolId, String detail) {
        issues.add(new AuditIssue(kind, toolId, detail));
    }

    public record AuditIssue(String kind, String toolId, String detail) { }
}
