package com.agentscopea2a.v2.skillManager.dto;

/**
 * 通知收件人条目(通知收件人页面/发送侧共用)。
 * userId 为人员表校验过的统一认证号;displayName 为人员快照中的姓名,查不到时为 null。
 */
public record NotificationRecipientDto(
        /** 收件人用户 ID(统一认证号)。 */
        String userId,
        /** 显示名称(人员表"姓名"),人员快照中不存在时为 null。 */
        String displayName,
        /** 该收件人关系是否启用。 */
        Boolean enabled) {}
