package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;
import java.util.Map;

/**
 * 更新通知设置的请求体(全量替换,空列表 = 清空名单恢复兜底行为)。
 * job 只使用 notifyReceivers;flow 的 notifyReceiverTriggers 可空(默认仅 AUTO_METRIC 发名单);
 * flow 的 notifyEnabled 为完成通知开关(null = 不修改,保持原值),归属 skill_flow.notify_enabled,
 * 页面勾选状态经此字段落库,保证「页面读取 → 请求 DTO → Service 更新 → 数据库」链路一致。
 */
public record NotifySettingsUpdateRequest(
        List<String> notifyReceivers,
        List<String> notifyReceiverTriggers,
        Boolean notifyEnabled,
        /** 可选:按触发来源分别配置收件人;键为 AUTO_METRIC/MANUAL/CHAT。 */
        Map<String, List<String>> notifyReceiversByTrigger) {}
