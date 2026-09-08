package com.agentscopea2a.util;

import java.util.Collection;

/**
 * AI Chat 用户通知的公共出口。
 *
 * <p>具体通知渠道和提示内容由后续实现补充。</p>
 */
public final class AiChatNotificationUtil {

    private AiChatNotificationUtil() {
    }

    /**
     * 向单个用户发送通知。
     *
     * @param userId  用户 ID
     * @param message 提示信息
     */
    public static void send(String userId, String message) {
        // 具体通知方式和提示内容待实现
    }

    /**
     * 向多个用户发送通知。
     *
     * @param userIds 用户 ID 集合
     * @param message 提示信息
     */
    public static void send(Collection<String> userIds, String message) {
        // 具体通知方式和提示内容待实现
    }
}
