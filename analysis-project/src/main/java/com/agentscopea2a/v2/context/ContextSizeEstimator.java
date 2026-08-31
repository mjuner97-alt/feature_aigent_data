package com.agentscopea2a.v2.context;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;

/** Offline estimator; intentionally independent from provider-specific tokenizers. */
public final class ContextSizeEstimator {

    private ContextSizeEstimator() {
    }

    public static ContextSizeSnapshot estimate(ReasoningInput input) {
        int messageCount = 0;
        int totalChars = 0;
        int toolResultChars = 0;
        int largestBlockChars = 0;
        if (input != null && input.messages() != null) {
            for (Msg message : input.messages()) {
                if (message == null) continue;
                messageCount++;
                if (message.getContent() == null) continue;
                for (ContentBlock block : message.getContent()) {
                    int chars = textLength(block);
                    totalChars += chars;
                    largestBlockChars = Math.max(largestBlockChars, chars);
                    if (block instanceof ToolResultBlock) toolResultChars += chars;
                }
            }
        }
        int toolSchemaChars = input == null || input.tools() == null ? 0 : input.tools().stream()
                .mapToInt(tool -> tool == null ? 0 : tool.toString().length()).sum();
        totalChars += toolSchemaChars;
        int estimatedTokens = estimateTokens(totalChars);
        return new ContextSizeSnapshot(messageCount, totalChars, toolSchemaChars,
                toolResultChars, largestBlockChars, estimatedTokens);
    }

    static int estimateTokens(int chars) {
        if (chars <= 0) return 0;
        return (int) Math.ceil(chars / 4.0);
    }

    private static int textLength(ContentBlock block) {
        if (block instanceof TextBlock text && text.getText() != null) {
            return text.getText().length();
        }
        return block == null ? 0 : block.toString().length();
    }
}
