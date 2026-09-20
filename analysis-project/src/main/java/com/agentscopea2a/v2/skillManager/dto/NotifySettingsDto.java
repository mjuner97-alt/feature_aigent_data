package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 定时任务 / 长任务流程的通知设置(通知设置抽屉专用,与任务创建/编辑表单解耦)。
 * 收件人为人员表校验过的 userId(统一认证号)列表;发送时整名单一次批量透传邮件 toUserList。
 */
public record NotifySettingsDto(
        /** 完成通知收件人名单;空 = 未配置(发送侧兜底:job 发创建人,flow 发触发人)。 */
        List<String> notifyReceivers,
        /** 仅流程有:哪些触发类型发名单(CHAT/MANUAL/AUTO_METRIC);null = 未配置(默认仅 AUTO_METRIC)。 */
        List<String> notifyReceiverTriggers,
        /** 仅流程有:完成通知开关;job 侧恒为 null(job 的发送时机由依赖指标的 notify_enabled 控制)。 */
        Boolean notifyEnabled) {}
