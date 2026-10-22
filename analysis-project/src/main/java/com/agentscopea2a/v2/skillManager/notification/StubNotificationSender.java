package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.constant.AgentToolConstant;
import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.util.HttpClientUtil;
import com.agentscopea2a.util.SignUtil;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal HTTP mail gateway adapter (legacy class name retained for compatibility).
 */
@Component
public class StubNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);
    private static final String APP_SECRET = "9dc9a6e7cb8d49d08e0df8464764cd63";
    private static final String APP_KEY = "555147722";
    private static final String DEFAULT_TITLE = "skill job 通知";
    private final AiChatRuntimeConfigMapper runtimeConfigMapper;
    @Value("${skill.notification.mail.success-codes:0,200}")
    private String successCodes = "0,200";
    @Value("${skill.notification.mail.url:}")
    private String mailUrl;

    public StubNotificationSender(AiChatRuntimeConfigMapper runtimeConfigMapper) {
        this.runtimeConfigMapper = runtimeConfigMapper;
    }

    @Override
    public void send(NotificationPayload p) {
        sendPrepared(p, prepare(p));
    }

    @Override
    public Map<String, Object> prepare(NotificationPayload payload) {
        Map<String, Object> request = NotificationSender.super.prepare(payload);
        request.put("content", getMailContent(payload.content(), payload.notifyContactName(), payload.notifyContactPhone(), payload.executionDate()));
        return request;
    }

    @Override
    public String sendPrepared(NotificationPayload payload, Map<String, Object> request) {
        return post(request);
    }

    public void send(String userId, String content) {
        send(DEFAULT_TITLE, content, List.of(userId));
    }

    public void send(String userId, String title, String content) {
        send(title, content, List.of(userId));
    }

    private void send(String title, String content, List<String> userIds) {
        send(title, content, userIds, List.of(), null, null);
    }

    private void send(String title, String content, List<String> userIds, List<String> ccUserIds,
                      String contactName, String contactPhone) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("title", title);
        requestBody.put("content", getMailContent(content, contactName, contactPhone));
        requestBody.put("toUserList", userIds == null ? List.of() : new ArrayList<>(userIds));
        requestBody.put("toCCUserList", ccUserIds == null ? List.of() : new ArrayList<>(ccUserIds));
        post(requestBody);
    }

    private String post(Map<String, Object> requestBody) {
        String timeStamp = String.valueOf(System.currentTimeMillis());
        String sign = new SignUtil().generateSign(timeStamp, timeStamp, APP_KEY, APP_SECRET);
        Map<String, String> requestHeader = new HashMap<>();
        requestHeader.put("x-token", AgentToolConstant.DEFAULT_XTOKEN);
        requestHeader.put("x-appkey", APP_KEY);
        requestHeader.put("x-timestamp", timeStamp);
        requestHeader.put("x-nonce", timeStamp);
        requestHeader.put("x-sign", sign);
        try {
            String response = HttpClientUtil.postWithHeaders(
                    mailUrl == null || mailUrl.isBlank() ? AgentToolConstant.DEFAULT_MAIL_URL : mailUrl,
                    requestBody, requestHeader);
            MailGatewayResponse.requireAccepted(response, successCodes);
            return response;
        } catch (MailGatewayResponse.Rejected e) {
            throw e;
        } catch (HttpClientUtil.HttpFailure e) {
            throw new MailGatewayResponse.Rejected(e.getMessage(), e.response());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("MailGatewaySendFailed: " + e.getMessage(), e);
        }
    }

    private String getMailContent(String mailContent, String contactName, String contactPhone) {
        return getMailContent(mailContent, contactName, contactPhone, null);
    }

    private String getMailContent(String mailContent, String contactName, String contactPhone, java.time.LocalDate executionDate) {
        String defaultHtml = NotificationContact.hasCustom(contactName, contactPhone) ? "" : notificationContactHtml();
        return NotificationContact.append(mailContent, contactName, contactPhone, defaultHtml, executionDate);
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
