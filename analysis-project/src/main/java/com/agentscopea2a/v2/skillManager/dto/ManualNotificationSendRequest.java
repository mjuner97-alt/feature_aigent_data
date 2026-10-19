package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** Manual notification confirmation payload; optional for legacy clients. */
public record ManualNotificationSendRequest(Boolean confirmed, List<String> notifyReceivers, List<String> ccReceivers) {
    public ManualNotificationSendRequest(Boolean confirmed, List<String> notifyReceivers) {
        this(confirmed, notifyReceivers, null);
    }
}