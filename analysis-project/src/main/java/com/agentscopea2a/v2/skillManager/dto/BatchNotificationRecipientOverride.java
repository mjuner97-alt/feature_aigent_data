package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** 批量发送中对单份团队报告收件人的本次临时调整，不回写任务配置。 */
public record BatchNotificationRecipientOverride(
    Long executionId,
    List<String> to,
    List<String> cc
) {}
