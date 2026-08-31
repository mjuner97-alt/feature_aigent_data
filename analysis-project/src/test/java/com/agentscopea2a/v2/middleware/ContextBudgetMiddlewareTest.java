package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.context.ContextBudgetProperties;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextBudgetMiddlewareTest {

    @Test
    void belowBudgetPassesThrough() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        ContextBudgetMiddleware middleware = new ContextBudgetMiddleware(props, null);
        ReasoningInput input = inputWithChars(1000);
        assertDoesNotThrow(() -> middleware.onReasoning(null, null, input, ignored -> reactor.core.publisher.Flux.empty()).blockLast());
    }

    @Test
    void hardBudgetRejectsWhenSingleRequestCannotBeCompacted() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setMaxInputTokens(100);
        ContextBudgetMiddleware middleware = new ContextBudgetMiddleware(props, null);
        ReasoningInput input = inputWithChars(1000);
        assertThrows(ContextBudgetMiddleware.ContextBudgetExceededException.class,
                () -> middleware.onReasoning(null, null, input, ignored -> reactor.core.publisher.Flux.empty()).blockLast());
    }

    @Test
    void disabledBudgetPassesThrough() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setEnabled(false);
        props.setMaxInputTokens(1);
        ContextBudgetMiddleware middleware = new ContextBudgetMiddleware(props, null);
        assertDoesNotThrow(() -> middleware.onReasoning(null, null, inputWithChars(1000),
                ignored -> reactor.core.publisher.Flux.empty()).blockLast());
    }

    @Test
    void oversizedLatestToolResultIsReplacedByArtifactReference() throws Exception {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setMaxLatestToolTokens(10);
        ArtifactStore store = new ArtifactStore(Files.createTempDirectory("artifact-test"), "/workspace/artifacts", true);
        ContextBudgetMiddleware middleware = new ContextBudgetMiddleware(props, null, store,
                Set.of("script_exec"), 10);
        ToolResultBlock result = ToolResultBlock.of("call-1", "script_exec",
                List.of(TextBlock.builder().text("x".repeat(100)).build()));
        Msg message = Msg.builder().role(MsgRole.TOOL).content(result).build();
        ReasoningInput input = new ReasoningInput(List.of(message), List.of(), null);

        ReasoningInput compacted = middleware.compactLatestToolResult(input,
                io.agentscope.core.agent.RuntimeContext.builder().userId("u1").sessionId("s1").build());
        ToolResultBlock compactedResult = (ToolResultBlock) compacted.messages().get(0).getContent().get(0);
        String text = ((TextBlock) compactedResult.getOutput().get(0)).getText();
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("artifact"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("/workspace/artifacts/u1/s1/"));
    }

    private static ReasoningInput inputWithChars(int chars) {
        Msg message = Msg.builder().role(MsgRole.USER).textContent("x".repeat(chars)).build();
        return new ReasoningInput(List.of(message), List.of(), null);
    }
}
