package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** 跨分页批量通知选择；ALL 表示批次全选后排除，INCLUDE 表示只发送显式勾选项。 */
public record BatchNotificationRequest(
    Long flowId,
    String batchId,
    String selectionMode,
    List<Long> includedExecutionIds,
    List<Long> excludedExecutionIds,
    List<BatchNotificationRecipientOverride> recipientOverrides,
    Boolean confirmed
) {
    public BatchNotificationRequest(
        Long flowId,
        String batchId,
        String selectionMode,
        List<Long> includedExecutionIds,
        List<Long> excludedExecutionIds,
        Boolean confirmed
    ) {
        this(flowId, batchId, selectionMode, includedExecutionIds, excludedExecutionIds, List.of(), confirmed);
    }
}
