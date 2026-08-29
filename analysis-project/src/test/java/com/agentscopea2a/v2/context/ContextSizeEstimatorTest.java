package com.agentscopea2a.v2.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSizeEstimatorTest {

    @Test
    void estimatesAsciiAndChineseTextDeterministically() {
        Msg message = Msg.builder().role(MsgRole.USER).textContent("a".repeat(400) + "中".repeat(100)).build();
        ReasoningInput input = new ReasoningInput(List.of(message), List.of(), null);

        ContextSizeSnapshot snapshot = ContextSizeEstimator.estimate(input);

        assertEquals(500, snapshot.totalChars());
        assertTrue(snapshot.estimatedInputTokens() >= 125);
        assertTrue(snapshot.estimatedInputTokens() <= 130);
        assertEquals(1, snapshot.messageCount());
    }
}
