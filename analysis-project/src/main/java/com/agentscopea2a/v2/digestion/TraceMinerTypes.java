/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.digestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure data carriers and aggregation helpers for {@link TraceMiner}. Extracted from
 * {@code TraceMiner} (P2-1) so the SQL / file-IO / orchestration classes can share
 * these types without a circular dependency on the main class.
 *
 * <p>All records are package-private and immutable (except {@link TraceGroup}, which
 * is a mutable accumulator by design).
 */
final class TraceMinerTypes {

    private TraceMinerTypes() {
    }

    /** Result of classifying a session's tool results - score + success flag. */
    record FailureClass(int failureCount, double failureScore, boolean successful) {}

    /** Flattened episodic row - role + content text. */
    record RawMessage(String role, String content) {}

    /** Aggregated per-session trace ready for fingerprinting. */
    record RawSession(String sessionId, List<String> toolIds,
                      int failureCount, double failureScore, String lastSnippet,
                      boolean successful,
                      String userQuery, List<ToolCallDetail> details) {}

    /** L2 subagent spawn info extracted from TOOL agent_spawn message. */
    record SubAgentSpawn(String agentId, String subSessionId) {}

    /**
     * Tool call detail with input/output context for skill distillation.
     * Output is truncated to OUTPUT_MAX_LEN chars - only the result shape matters,
     * not the actual data values. Input is kept up to INPUT_MAX_LEN.
     */
    record ToolCallDetail(String tool, String level, String input, String output) {}

    /** Result of parsing a single L2 memory_messages.jsonl file. */
    record ParseL2Result(List<String> toolNames, List<ToolCallDetail> details) {
        static ParseL2Result empty() {
            return new ParseL2Result(List.of(), List.of());
        }
    }

    /**
     * Mutable accumulator for sessions sharing the same fingerprint. Tracks aggregate
     * failure score / count + first non-empty sample query + first session's tool-call
     * details (used as the representative for distillation).
     */
    static class TraceGroup {
        final String fingerprint;
        String runtimeFingerprint; // computed from userQuery via FingerprintCalculator
        final List<String> toolSequence;
        final List<RawSession> sessions = new ArrayList<>();
        double totalFailureScore = 0.0;
        int totalFailureCount = 0;
        int totalSuccess = 0;
        String sampleQuery = "";
        String userQuery = "";
        final List<ToolCallDetail> details = new ArrayList<>();

        TraceGroup(String fingerprint, List<String> toolSequence) {
            this.fingerprint = fingerprint;
            this.toolSequence = toolSequence;
        }

        void add(RawSession s) {
            sessions.add(s);
            totalFailureScore += s.failureScore;
            totalFailureCount += s.failureCount;
            if (s.successful) totalSuccess++;
            if (sampleQuery.isEmpty() && s.lastSnippet != null) {
                sampleQuery = s.lastSnippet;
            }
            if (userQuery.isEmpty() && s.userQuery != null) {
                userQuery = s.userQuery;
            }
            if (details.isEmpty() && s.details != null) {
                details.addAll(s.details);
            }
        }

        String sampleQuery() {
            return Truncation.truncate(sampleQuery, 500);
        }
    }

    /** Shared truncate helper - used by TraceMiner and TraceGroup. */
    static final class Truncation {
        private Truncation() {
        }

        static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }
}
