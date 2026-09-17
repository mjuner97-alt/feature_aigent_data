package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.constant.AgentToolConstant;
import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.util.HttpClientUtil;
import com.agentscopea2a.util.RxyNoticeUtil;
import com.agentscopea2a.util.SignUtil;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link NotificationSender} 默认 stub 实现：仅打印日志，不真正发送。
 *
 * <p>接入内部系统时，提供自己的 {@code @Component} 实现 {@link NotificationSender}，
 * 本 stub 因 {@code @ConditionalOnMissingBean} 自动退让。
 */
// 接入内部通知系统前先用本 stub：发送仅打日志；提供自定义 @Component 后本 bean 自动退让。
@Component
public class StubNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);
    private static final String APP_SECRET = "9dc9a6e7cb8d49d08e0df8464764cd63";
    private static final String APP_KEY = "555147722";
    private static final String DEFAULT_TITLE = "skill job 通知";
    private final AiChatRuntimeConfigMapper runtimeConfigMapper;

    public StubNotificationSender(AiChatRuntimeConfigMapper runtimeConfigMapper) {
        this.runtimeConfigMapper = runtimeConfigMapper;
    }

    @Override
    public void send(NotificationPayload p) {
        send(p.jobName(), p.content(), p.userIdList());
    }

    public void send(String userId, String content) {
        send(DEFAULT_TITLE, content, List.of(userId));
    }

    public void send(String userId, String title, String content) {
        send(title, content, List.of(userId));
    }

    private void send(String title, String content, List<String> userIds) {
        String timeStamp = String.valueOf(System.currentTimeMillis());
        String sign = new SignUtil().generateSign(timeStamp, timeStamp, APP_KEY, APP_SECRET);
        Map<String, String> requestHeader = new HashMap<>();
        requestHeader.put("x-token", AgentToolConstant.DEFAULT_XTOKEN);
        requestHeader.put("x-appkey", APP_KEY);
        requestHeader.put("x-timestamp", timeStamp);
        requestHeader.put("x-nonce", timeStamp);
        requestHeader.put("x-sign", sign);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("title", title);
        requestBody.put("content", getMailContent(content));
        requestBody.put("toUserList", userIds == null ? List.of() : new ArrayList<>(userIds));
        try {
            String response = HttpClientUtil.postWithHeaders(
                    AgentToolConstant.DEFAULT_MAIL_URL, requestBody, requestHeader);
            log.info("[Notification STUB] sent to {}, response={}", userIds, response);
        } catch (Exception e) {
            log.warn("[Notification STUB] send failed, falling back to notice", e);
            if (userIds != null && !userIds.isEmpty() && userIds.get(0) != null) {
                RxyNoticeUtil.sendNotice(userIds.get(0), "skill job 已经生成, 请去页面查看...");
            }
        }
    }

    private String getMailContent(String mailContent) {
        String contact = notificationContactHtml();
        String content = String.valueOf(mailContent);
        if (contact.isBlank()) {
            return content;
        }
        int bodyEnd = content.toLowerCase().lastIndexOf("</body>");
        if (bodyEnd >= 0) {
            return content.substring(0, bodyEnd) + contact + content.substring(bodyEnd);
        }
        return content + contact;
    }

    private String notificationContactHtml() {
        try {
            AiChatRuntimeConfig config = runtimeConfigMapper.selectByConfigKey(
                    AiChatRuntimeConfigKeys.NOTIFICATION_CONTACT_HTML);
            return config == null || config.getConfigValue() == null ? "" : config.getConfigValue();
        } catch (Exception e) {
            log.warn("[Notification STUB] load contact html failed: {}", e.getMessage());
            return "";
        }
    }
}
