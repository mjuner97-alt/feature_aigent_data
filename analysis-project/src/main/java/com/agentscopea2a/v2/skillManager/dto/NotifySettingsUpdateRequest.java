package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 更新通知设置的请求体(全量替换,空列表 = 清空名单恢复兜底行为)。
 * job 只使用 notifyReceivers;flow 的 notifyReceiverTriggers 可空(默认仅 AUTO_METRIC 发名单)。
 */
public record NotifySettingsUpdateRequest(
        List<String> notifyReceivers,
        List<String> notifyReceiverTriggers) {}
