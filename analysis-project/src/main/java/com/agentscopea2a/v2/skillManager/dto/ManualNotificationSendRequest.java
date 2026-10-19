package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** Manual notification confirmation payload; optional for legacy clients. */
public record ManualNotificationSendRequest(Boolean confirmed, List<String> notifyReceivers, List<String> ccReceivers,
                                            String notifyBodyTemplate) {
    public ManualNotificationSendRequest {
        if (notifyBodyTemplate != null && notifyBodyTemplate.length() > 2000) {
            throw new IllegalArgumentException("通知邮件正文模板超长（上限 2000 字符）");
        }
    }
    public ManualNotificationSendRequest(Boolean confirmed, List<String> notifyReceivers, List<String> ccReceivers) {
        this(confirmed, notifyReceivers, ccReceivers, null);
    }
    public ManualNotificationSendRequest(Boolean confirmed, List<String> notifyReceivers) {
        this(confirmed, notifyReceivers, null);
    }
}
