package com.agentscopea2a.v2.context;

/** Deterministic, offline estimate of one model input. */
public record ContextSizeSnapshot(
        int messageCount,
        int totalChars,
        int toolSchemaChars,
        int toolResultChars,
        int largestBlockChars,
        int estimatedInputTokens) {
}
