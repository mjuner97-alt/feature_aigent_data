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
        int estimatedTokens = 0;
        if (input != null && input.messages() != null) {
            for (Msg message : input.messages()) {
                if (message == null || message.getContent() == null) continue;
                for (ContentBlock block : message.getContent()) {
                    estimatedTokens += estimateTextTokens(textValue(block));
                }
            }
        }
        if (input != null && input.tools() != null) {
            for (Object tool : input.tools()) {
                if (tool != null) estimatedTokens += estimateTextTokens(tool.toString());
            }
        }
        return new ContextSizeSnapshot(messageCount, totalChars, toolSchemaChars,
                toolResultChars, largestBlockChars, estimatedTokens);
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int ascii = 0;
        int other = 0;
        int tokens = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f) {
                ascii++;
                continue;
            }
            if (ascii > 0) {
                tokens += (int) Math.ceil(ascii / 4.0);
                ascii = 0;
            }
            if (isCjk(codePoint)) {
                if (other > 0) {
                    tokens += (int) Math.ceil(other / 1.5);
                    other = 0;
                }
                tokens++;
            } else {
                other++;
            }
        }
        if (ascii > 0) tokens += (int) Math.ceil(ascii / 4.0);
        if (other > 0) tokens += (int) Math.ceil(other / 1.5);
        return tokens;
    }

    private static int textLength(ContentBlock block) {
        return textValue(block).length();
    }

    private static String textValue(ContentBlock block) {
        if (block instanceof TextBlock text && text.getText() != null) return text.getText();
        return block == null ? "" : block.toString();
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x2e80 && codePoint <= 0x9fff)
                || (codePoint >= 0xac00 && codePoint <= 0xd7af)
                || (codePoint >= 0xa960 && codePoint <= 0xa97f)
                || (codePoint >= 0x3040 && codePoint <= 0x30ff)
                || (codePoint >= 0x31f0 && codePoint <= 0x31ff);
    }
}
