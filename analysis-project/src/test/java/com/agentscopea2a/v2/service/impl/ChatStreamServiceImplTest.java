package com.agentscopea2a.v2.service.impl;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.tools.ToolCallCollector;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamServiceImplTest {

    @Test
    void configuresAiChatContextForScriptOutputEvents() {
        RuntimeContext context = RuntimeContext.builder()
                .sessionId("conversation-1")
                .userId("user-1")
                .build();
        SseEmitter emitter = new SseEmitter();
        ChatRequest request = new ChatRequest();
        request.setConversationId("answer-1");
        request.setAgentId("agent-1");
        request.setAgentName("agent-name");
        request.setFromType("web");

        ChatStreamServiceImpl.configureScriptOutputContext(
                context, emitter, request, "conversation-1", "query");

        assertSame(emitter, context.get(ToolCallTrackingHook.EMITTER_CTX_KEY));
        assertTrue(Boolean.TRUE.equals(
                context.get(ToolCallTrackingHook.SCRIPT_OUTPUT_ONLY_CTX_KEY)));
        ToolCallTrackingHook.SseMeta meta = context.get(ToolCallTrackingHook.SSE_META_CTX_KEY);
        assertNotNull(meta);
        assertEquals("answer-1", meta.ansUUID());
        ToolCallCollector collector = context.get(ToolCallTrackingHook.COLLECTOR_CTX_KEY);
        assertNotNull(collector);
        assertEquals("query", collector.getUserQuery());
    }
}
