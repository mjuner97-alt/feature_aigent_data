package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.BatchNotificationRequest;
import com.agentscopea2a.v2.skillManager.dto.BatchNotificationRecipientOverride;
import com.agentscopea2a.v2.skillManager.entity.FlowExecutionStatus;
import com.agentscopea2a.v2.skillManager.entity.FlowTriggerType;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlow;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationReceivers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 团队报告批次通知：服务端解析跨分页选择，并保证预览与发送使用同一批执行快照。 */
@Service
public class FlowBatchNotificationService {
    private final SkillFlowMapper mapper;
    private final FlowCompletionService completionService;
    private final MockOrgService orgService;
    private final TeamReportService teamReports;

    public FlowBatchNotificationService(
        SkillFlowMapper mapper,
        FlowCompletionService completionService,
        MockOrgService orgService,
        TeamReportService teamReports
    ) {
        this.mapper = mapper;
        this.completionService = completionService;
        this.orgService = orgService;
        this.teamReports = teamReports;
    }

    public BatchPreview preview(BatchNotificationRequest request, String userId) {
        List<SkillFlowExecution> selected = selected(request, userId);
        Set<String> allTo = new LinkedHashSet<>(), allCc = new LinkedHashSet<>();
        Map<Long, BatchNotificationRecipientOverride> overrides = overrides(request, selected);
        List<Prepared> prepared = selected.stream().map(item -> prepare(item, overrides.get(item.getId()))).toList();
        prepared.forEach(item -> {
            allTo.addAll(item.validTo());
            allCc.addAll(item.validCc());
        });
        allCc.removeAll(allTo);
        Set<String> people = new LinkedHashSet<>();
        prepared.forEach(item -> {
            people.addAll(item.configuredTo());
            people.addAll(item.configuredCc());
        });
        Map<String, String> names = orgService.getUserNameMap(people);
        List<PreviewItem> items = prepared
            .stream()
            .map(item -> new PreviewItem(
                item.execution().getId(),
                FlowReportNaming.notificationTitle(item.execution()),
                item.configuredTo().stream().map(id -> new Recipient(id, names.getOrDefault(id, id), item.validTo().contains(id))).toList(),
                item.configuredCc().stream().map(id -> new Recipient(id, names.getOrDefault(id, id), item.validCc().contains(id))).toList(),
                !item.validTo().isEmpty()
            ))
            .toList();
        return new BatchPreview(
            items,
            items.size(),
            (int) items.stream().filter(PreviewItem::sendable).count(),
            allTo.size(),
            allCc.size()
        );
    }

    public BatchSendResult send(BatchNotificationRequest request, String userId) {
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new IllegalArgumentException("NotificationConfirmationRequired: 请先预览并确认收件人");
        }
        List<Long> sent = new ArrayList<>(), failed = new ArrayList<>();
        List<SkillFlowExecution> selected = selected(request, userId);
        Map<Long, BatchNotificationRecipientOverride> overrides = overrides(request, selected);
        for (SkillFlowExecution execution : selected) {
            Prepared item = prepare(execution, overrides.get(execution.getId()));
            if (item.validTo().isEmpty()) {
                failed.add(execution.getId());
                continue;
            }
            try {
                completionService.resend(execution, item.validTo(), item.validCc(), null);
                sent.add(execution.getId());
            } catch (RuntimeException e) {
                failed.add(execution.getId());
            }
        }
        return new BatchSendResult(sent, failed);
    }

    private List<SkillFlowExecution> selected(BatchNotificationRequest request, String userId) {
        if (request == null || request.flowId() == null || request.batchId() == null || request.batchId().isBlank()) {
            throw new IllegalArgumentException("BatchNotificationSelectionRequired");
        }
        List<SkillFlowExecution> batch = mapper.selectObjectBatch(request.flowId(), request.batchId());
        if (batch.isEmpty()) throw new IllegalStateException("BatchNotificationNotFound");
        if (batch.stream().anyMatch(e -> !userId.equals(e.getTriggerUserId()))) {
            throw new IllegalStateException("FlowAccessDenied: 仅本次执行触发人可批量发送通知");
        }
        Set<Long> included = new LinkedHashSet<>(safe(request.includedExecutionIds()));
        Set<Long> excluded = new LinkedHashSet<>(safe(request.excludedExecutionIds()));
        boolean all = "ALL".equalsIgnoreCase(request.selectionMode());
        if (!all && !"INCLUDE".equalsIgnoreCase(request.selectionMode())) {
            throw new IllegalArgumentException("BatchNotificationSelectionModeInvalid");
        }
        Set<Long> batchIds = batch.stream().map(SkillFlowExecution::getId).collect(java.util.stream.Collectors.toSet());
        if ((!all && !batchIds.containsAll(included)) || (all && !batchIds.containsAll(excluded))) {
            throw new IllegalArgumentException("BatchNotificationSelectionOutsideBatch");
        }
        List<SkillFlowExecution> selected = batch
            .stream()
            .filter(e -> all ? !excluded.contains(e.getId()) : included.contains(e.getId()))
            .filter(this::eligible)
            .toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("BatchNotificationSelectionRequired");
        return selected;
    }

    private boolean eligible(SkillFlowExecution execution) {
        FlowExecutionStatus status = execution.getStatus();
        return execution.getBatchId() != null &&
            execution.getTriggerType() == FlowTriggerType.MANUAL &&
            status != null && status.terminal() && status != FlowExecutionStatus.CANCELLED &&
            execution.getReportPath() != null && !execution.getReportPath().isBlank();
    }

    private Prepared prepare(SkillFlowExecution execution, BatchNotificationRecipientOverride override) {
        List<String> configuredTo = NotificationReceivers.parse(execution.getNotifyReceiversSnapshot());
        List<String> configuredCc = NotificationReceivers.parse(execution.getCcReceiversSnapshot());
        SkillFlow flow = mapper.selectFlowById(execution.getFlowId());
        if (flow != null) {
            var config = teamReports.config(flow);
            var target = config == null
                ? null
                : config.targets().stream()
                    .filter(item -> java.util.Objects.equals(item.teamId(), execution.getTeamId()))
                    .findFirst()
                    .orElse(null);
            if (target != null) {
                configuredTo = target.to();
                configuredCc = target.cc();
            }
        }
        if (override != null) {
            configuredTo = override.to() == null ? List.of() : override.to();
            configuredCc = override.cc() == null ? List.of() : override.cc();
        }
        List<String> normalizedTo = NotificationReceivers.parse(NotificationReceivers.toCsv(configuredTo));
        List<String> normalizedCc = NotificationReceivers.parse(NotificationReceivers.toCsv(configuredCc))
            .stream()
            .filter(id -> !normalizedTo.contains(id))
            .toList();
        List<String> validTo = orgService.filterExistingUserIds(normalizedTo);
        List<String> validCc = orgService
            .filterExistingUserIds(normalizedCc)
            .stream()
            .filter(id -> !validTo.contains(id))
            .toList();
        return new Prepared(execution, normalizedTo, normalizedCc, validTo, validCc);
    }

    private Map<Long, BatchNotificationRecipientOverride> overrides(
        BatchNotificationRequest request,
        List<SkillFlowExecution> selected
    ) {
        List<BatchNotificationRecipientOverride> values = request.recipientOverrides() == null
            ? List.of()
            : request.recipientOverrides();
        Set<Long> selectedIds = selected.stream().map(SkillFlowExecution::getId).collect(java.util.stream.Collectors.toSet());
        if (values.stream().anyMatch(value -> value == null || value.executionId() == null || !selectedIds.contains(value.executionId()))) {
            throw new IllegalArgumentException("BatchNotificationOverrideOutsideSelection");
        }
        return values.stream().collect(java.util.stream.Collectors.toMap(
            BatchNotificationRecipientOverride::executionId,
            java.util.function.Function.identity(),
            (left, right) -> right,
            java.util.LinkedHashMap::new
        ));
    }

    private static List<Long> safe(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private record Prepared(
        SkillFlowExecution execution,
        List<String> configuredTo,
        List<String> configuredCc,
        List<String> validTo,
        List<String> validCc
    ) {}
    public record Recipient(String userId, String name, boolean valid) {}
    public record PreviewItem(Long executionId, String reportName, List<Recipient> to, List<Recipient> cc, boolean sendable) {}
    public record BatchPreview(List<PreviewItem> items, int selectedCount, int sendableCount, int recipientCount, int ccCount) {}
    public record BatchSendResult(List<Long> sentExecutionIds, List<Long> failedExecutionIds) {}
}
