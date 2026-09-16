package com.agentscopea2a.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 降级通知占位实现。
 */
public final class RxyNoticeUtil {
    private static final Logger log = LoggerFactory.getLogger(RxyNoticeUtil.class);

    private RxyNoticeUtil() {
    }

    public static void sendNotice(String userId, String content) {
        log.warn("[Notification FALLBACK] userId={}, content={}", userId, content);
    }
}
