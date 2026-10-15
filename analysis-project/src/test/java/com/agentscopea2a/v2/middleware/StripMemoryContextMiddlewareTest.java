package com.agentscopea2a.v2.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripMemoryContextMiddlewareTest {

    @Test
    void closedSectionIsRemoved() {
        String text = "header\n<memory_context>\nsome leaked memory\n</memory_context>\ntail";
        String out = StripMemoryContextMiddleware.stripMemoryContext(text);
        assertFalse(out.contains("memory_context"));
        assertFalse(out.contains("leaked memory"));
        assertTrue(out.contains("header"));
        assertTrue(out.contains("tail"));
    }

    @Test
    void unclosedTailIsRemoved() {
        String text = "header\n<memory_context>\ntruncated prompt without closing tag";
        String out = StripMemoryContextMiddleware.stripMemoryContext(text);
        assertFalse(out.contains("memory_context"));
        assertTrue(out.contains("header"));
    }

    @Test
    void textWithoutTagIsUnchanged() {
        assertEquals("plain prompt", StripMemoryContextMiddleware.stripMemoryContext("plain prompt"));
    }

    @Test
    void systemMsgIsRebuiltWithoutMemoryContext() {
        Msg system = Msg.builderForRole(MsgRole.SYSTEM)
                .name("system")
                .textContent("before\n<memory_context>\nleak\n</memory_context>\nafter")
                .build();
        Msg stripped = StripMemoryContextMiddleware.stripIfSystemMsg(system);
        assertNotEquals(system, stripped);
        String text = stripped.getTextContent();
        assertFalse(text.contains("memory_context"));
        assertTrue(text.contains("before"));
        assertTrue(text.contains("after"));
        assertEquals(MsgRole.SYSTEM, stripped.getRole());
    }

    @Test
    void nonSystemMsgIsUntouched() {
        Msg user = Msg.builderForRole(MsgRole.USER)
                .textContent("<memory_context>leak</memory_context>")
                .build();
        assertSame(user, StripMemoryContextMiddleware.stripIfSystemMsg(user));
    }

    @Test
    void systemMsgWithoutTagIsUntouched() {
        Msg system = Msg.builderForRole(MsgRole.SYSTEM)
                .textContent("clean prompt")
                .build();
        assertSame(system, StripMemoryContextMiddleware.stripIfSystemMsg(system));
    }

    @Test
    void userVisibleTextBlockIsPreserved() {
        // 非 system 消息里的 TextBlock 即使含同名标签也不动 (用户引用文档等场景)
        TextBlock block = TextBlock.builder().text("see <memory_context> in docs").build();
        assertEquals("see <memory_context> in docs", block.getText());
    }
}
