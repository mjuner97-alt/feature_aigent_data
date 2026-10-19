package com.agentscopea2a.v2.skillManager.entity;

/**
 * 通知投递状态:PENDING -> SENDING -> SIMULATED / ACCEPTED / FAILED；SENT 兼容历史记录。
 */
public enum FlowNotificationStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    SIMULATED,
    ACCEPTED
}
