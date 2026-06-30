
package com.agentscopea2a.controller;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.service.ChatStreamServiceV_3;
import com.agentscopea2a.service.ChatStreamService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/ai")
@CrossOrigin(origins = "*",maxAge = 3600)
public class ChatController {

    @Autowired
    private ChatStreamServiceV_3 chatStreamServiceV3;

    @Autowired
    private ChatStreamService chatStreamService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        // 统一归一化：chatId（公开入口）和 conversationId（Manager入口）是同一字段
        normalizeConversationId(req);
        if (StringUtils.isNoneEmpty(req.getAgentName())){
            return chatStreamServiceV3.stream(req);
        }else {
            req.setAgentId("7");
            req.setAgentName("数字QA助手");
            req.setFromType("HXY");
            return chatStreamServiceV3.streamPublic(req);
        }
    }

    @PostMapping(value = "/chatA2A", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatA2A(@RequestBody ChatRequest req) {
        // 统一归一化：chatId（公开入口）和 conversationId（Manager入口）是同一字段
        normalizeConversationId(req);
        if (StringUtils.isNoneEmpty(req.getAgentName())){
            return chatStreamServiceV3.stream(req);
        }else {
            req.setAgentId("7");
            req.setAgentName("数字QA助手");
            req.setFromType("HXY");
            return chatStreamServiceV3.streamPublic(req);
        }
    }

    /**
     * 两个入口用不同字段传会话ID，统一归一化到 conversationId：
     * <ul>
     *   <li>Manager 入口 → conversation_id</li>
     *   <li>公开入口 → chat_id</li>
     * </ul>
     * chatId 优先级低于 conversationId：当 conversationId 为空时，用 chatId 回填。
     */
    private void normalizeConversationId(ChatRequest req) {
        if (StringUtils.isNoneEmpty(req.getChatId()) && StringUtils.isEmpty(req.getConversationId())) {
            req.setConversationId(req.getChatId());
        }

        if (StringUtils.isEmpty(req.getConversationId())){
            req.setConversationId(UUID.randomUUID().toString());
        }
    }
}
