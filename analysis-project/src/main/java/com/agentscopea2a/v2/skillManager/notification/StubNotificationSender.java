package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.constant.AgentToolConstant;
import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.util.HttpClientUtil;
import com.agentscopea2a.util.SignUtil;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private String successCodes = "0,200";

    @Value("${skill.notification.mail.url:}")
    private String mailUrl;

    /**
     * true 继续调用内部 HTTP 邮件网关；false 将通知内容写成本地 HTML 文件。
     * 可在 application-*.properties 中通过 skill.notification.mail.http-enabled 调整。
     */
    private boolean httpEnabled = true;
    private String localHtmlDirectory = "src/main/resources/notification-html";

    private static final DateTimeFormatter LOCAL_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

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
        request.put(
            "content",
            getMailContent(
                payload.content(),
                payload.notifyContactName(),
                payload.notifyContactPhone(),
                payload.executionDate()
            )
        );
        if (payload.recipientLinks() != null && !payload.recipientLinks().isEmpty()) {
            Map<String, String> recipientContents = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> entry : payload.recipientLinks().entrySet()) {
                String content = payload
                    .content()
                    .replace("{链接}", entry.getValue())
                    .replace(payload.fileUrl(), entry.getValue());
                recipientContents.put(
                    entry.getKey(),
                    getMailContent(
                        content,
                        payload.notifyContactName(),
                        payload.notifyContactPhone(),
                        payload.executionDate()
                    )
                );
            }
            request.put("recipientContents", recipientContents);
        }
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

    @Override
    public void sendPasswordReset(String userId, String code) {
        String content =
            "<html><body>" +
            "<p>您好，您的密码找回验证码为：<strong>" +
            code +
            "</strong>。</p>" +
            "<p>验证码10分钟内有效，仅可使用一次。</p>" +
            "</body></html>";
        send(userId, "密码找回验证码", content);
    }

    private void send(String title, String content, List<String> userIds) {
        send(title, content, userIds, List.of(), null, null);
    }

    private void send(
        String title,
        String content,
        List<String> userIds,
        List<String> ccUserIds,
        String contactName,
        String contactPhone
    ) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("title", title);
        requestBody.put("content", getMailContent(content, contactName, contactPhone));
        requestBody.put("toUserList", userIds == null ? List.of() : new ArrayList<>(userIds));
        requestBody.put("toCCUserList", ccUserIds == null ? List.of() : new ArrayList<>(ccUserIds));
        post(requestBody);
    }

    private String post(Map<String, Object> requestBody) {
        if (!httpEnabled) {
            return writeLocalHtml(requestBody);
        }
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
                requestBody,
                requestHeader
            );
            MailGatewayResponse.requireAccepted(response, successCodes);
            return response;
        } catch (MailGatewayResponse.Rejected e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("MailGatewaySendFailed: " + e.getMessage(), e);
        }
    }

    /** 将通知请求写入本地 HTML 文件，返回生成文件的绝对路径。 */
    private String writeLocalHtml(Map<String, Object> requestBody) {
        String title = String.valueOf(requestBody.getOrDefault("title", DEFAULT_TITLE));
        String content = String.valueOf(requestBody.getOrDefault("content", ""));
        String safeTitle = title.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (safeTitle.isBlank()) {
            safeTitle = "notification";
        }
        Path directory = Paths.get(localHtmlDirectory).toAbsolutePath().normalize();
        Path file = directory.resolve(safeTitle + "-" + LocalDateTime.now().format(LOCAL_FILE_TIME) + ".html");
        String html = content.trim().toLowerCase().startsWith("<html")
            ? content
            : "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" +
              escapeHtml(title) +
              "</title></head><body>" +
              content.replace("\n", "<br>\n") +
              "</body></html>";
        try {
            Files.createDirectories(directory);
            Files.writeString(file, html, StandardCharsets.UTF_8);
            log.info("[Notification STUB] HTTP disabled, notification written to {}", file);
            return file.toString();
        } catch (Exception e) {
            throw new IllegalStateException("LocalNotificationFileWriteFailed: " + e.getMessage(), e);
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String getMailContent(String mailContent, String contactName, String contactPhone) {
        return getMailContent(mailContent, contactName, contactPhone, null);
    }

    private String getMailContent(
        String mailContent,
        String contactName,
        String contactPhone,
        java.time.LocalDate executionDate
    ) {
        String defaultHtml = NotificationContact.hasCustom(contactName, contactPhone) ? "" : notificationContactHtml();
        return NotificationContact.append(mailContent, contactName, contactPhone, defaultHtml, executionDate);
    }

    private String notificationContactHtml() {
        try {
            AiChatRuntimeConfig config = runtimeConfigMapper.selectByConfigKey(
                AiChatRuntimeConfigKeys.NOTIFICATION_CONTACT_HTML
            );
            return config == null || config.getConfigValue() == null ? "" : config.getConfigValue();
        } catch (Exception e) {
            log.warn("[Notification STUB] load contact html failed: {}", e.getMessage());
            return "";
        }
    }
}
