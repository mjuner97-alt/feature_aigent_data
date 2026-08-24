package com.agentscopea2a.v2.skillManager.entity;

/**
 * 通知投递状态:PENDING(已落库待发)-> SENT / FAILED。
 */
public enum FlowNotificationStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
