/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.digestion;

import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline tool-call trace miner that extracts per-session tool invocation sequences from
 * {@code episodic_memory} and writes the aggregated summary to {@code user_trace_summary}.
 *
 * <p>Runs in the nightly digestion pipeline ({@link MemoryDigestionService}, Phase 2).
 * Operates on the JDBC {@link DataSource} directly (not the {@link com.agentscopea2a.v2.memory.MySqlEpisodicMemory} API)
 * so we can batch-scan large session ranges without loading every row through the reactive
 * wrapper.
 *
 * <p><b>P2-1 split:</b> SQL operations extracted to {@link TraceMinerRepository}, L2 file
 * reading to {@link L2TraceReader}, data carriers to {@link TraceMinerTypes}. This class
 * keeps the orchestration ({@link #mineTraces}), pattern matching ({@link #buildSession}),
 * and failure classification ({@link #classifyTrace}).
 *
 * <h3>Failure classification</h3>
 * Each trace row is classified via {@link #classifyTrace}:
 * <ul>
 *   <li>{@code FAILURE} (weight 1.0) - python_exec non-zero exit, content contains "error" /
 *       "Exception" / "失败"</li>
 *   <li>{@code FAILURE} (weight 1.0) - tool_router returned a markdown table with only headers
 *       and no data rows (empty query result)</li>
 *   <li>{@code POSSIBLE_FAILURE} (weight 0.5) - session ended without a finish marker, or
 *       the last assistant message suggests a timeout / maxIters exhaustion</li>
 * </ul>
 */
public class TraceMiner {

    private static final Logger log = LoggerFactory.getLogger(TraceMiner.class);

    /** Regex patterns for failure detection. */
    private static final Pattern ERROR_PATTERN =
            Pattern.compile("(?i)(\\berror\\b|\\bexception\\b|失败|出错|超时|timeout)");
    private static final Pattern EXIT_CODE_PATTERN =
            Pattern.compile("exit\\s*=\\s*-[1-9]\\d*");
    private static final Pattern EMPTY_TABLE_PATTERN =
            Pattern.compile("\\|\\s*[-]+\\s*\\|.*\\|\\s*[-]+\\s*\\|\\s*\\n\\s*\\|\\s*$",
                    Pattern.MULTILINE);
    private static final Pattern TOOL_ROUTER_CALL =
            Pattern.compile("tool_router\\s*\\(.*?toolId\\s*=\\s*([a-z_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_ITERS_PATTERN =
            Pattern.compile("(?i)(max\\s*iters?|最大迭代|iteration.*exhaust|已达到最多)");
    private static final Pattern FINISH_MARKER =
            Pattern.compile("(?i)\\b(finish|完成|结束)\\b");

    /** Broader tool call pattern for JSON-like TOOL messages. */
    private static final Pattern BROAD_TOOL_CALL =
            Pattern.compile("\"(toolId|name)\"\\s*:\\s*\"([a-z_]+)\"");

    /**
     * Pattern to extract tool name from the "[TOOL: tool_name]" format stored by
     * MySqlEpisodicMemory.extractToolResultText().
     */
    private static final Pattern TOOL_BRACKET_PATTERN =
            Pattern.compile("\\[TOOL:\\s*([a-z_]+)\\]");

    /**
     * Pattern to detect subagent dispatch from supervisor text.
     * Matches patterns like "派单给 **query_data**", "派单给 `query_data`", or "派单给 query_data".
     */
    private static final Pattern SUBAGENT_DISPATCH =
            Pattern.compile("派单给\\s*[\\*`]{0,2}([a-z_]+)[\\*`]{0,2}");

    /**
     * Pattern to extract agent_id and session_id from agent_spawn TOOL messages.
     * Format: [TOOL: agent_spawn] "agent_key: ... agent_id: query_data ... session_id: sub-uuid ..."
     * Uses individual field lookups within the content string since the format is per-line.
     */
    private static final Pattern AGENT_SPAWN_HEADER =
            Pattern.compile("\\[TOOL:\\s*agent_spawn\\]");
    private static final Pattern AGENT_SPAWN_FIELD =
            Pattern.compile("agent_id:\\s*([a-z_]+)");
    private static final Pattern SUB_SESSION_FIELD =
            Pattern.compile("session_id:\\s*(sub-[\\w-]+)");

    /** Separator for merging L1 and L2 tool ID sequences. */
    private static final String L2_SEPARATOR = ">";

    /** Max chars for tool output snippet (output can be huge, input is what matters). */
    private static final int OUTPUT_MAX_LEN = 800;
    private static final int INPUT_MAX_LEN = 800;

    /**
     * Pattern to detect skill mentions in assistant text.
     * Matches query skill names like "query_quarterly_quality", "quality_query_*", etc.
     */
    private static final Pattern SKILL_NAME_IN_TEXT =
            Pattern.compile("\\b(query_quarterly|quality_query|defect_|department_quality|quarterly_dept|query_quality)[a-z_]*\\b");

    private static final double FAILURE_WEIGHT = 1.0;
    private static final double POSSIBLE_FAILURE_WEIGHT = 0.5;
    private static final int SNIPPET_MAX_LEN = 200;

    private final TraceMinerRepository repository;
    private final L2TraceReader l2Reader;
    private final int batchSize;
    private final FingerprintCalculator fingerprintCalc;

    /**
     * @param dataSource the JDBC DataSource (same pool used by MySqlEpisodicMemory)
     * @param tableName  the episodic memory table name (e.g. {@code QualitySupervisor_episodic_memory})
     * @param batchSize  max rows per batch query
     */
    public TraceMiner(DataSource dataSource, String tableName, int batchSize) {
        this(dataSource, tableName, batchSize, null, null);
    }

    /**
     * Backward-compatible constructor with workspace root but no FingerprintCalculator.
     *
     * @param dataSource     the JDBC DataSource
     * @param tableName      the episodic memory table name
     * @param batchSize      max rows per batch query
     * @param workspaceRoot  the harness workspace root (e.g. {@code .agentscope/workspace/harness-a2a}),
     *                       or {@code null} to skip L2 file reading
     */
    public TraceMiner(DataSource dataSource, String tableName, int batchSize, Path workspaceRoot) {
        this(dataSource, tableName, batchSize, workspaceRoot, null);
    }

    /**
     * Full constructor with workspace root and FingerprintCalculator for runtime fingerprint computation.
     *
     * @param dataSource       the JDBC DataSource
     * @param tableName        the episodic memory table name
     * @param batchSize        max rows per batch query
     * @param workspaceRoot    the harness workspace root, or {@code null} to skip L2 file reading
     * @param fingerprintCalc  the fingerprint calculator for runtime metric fingerprints,
     *                         or {@code null} to skip runtime fingerprint computation
     */
    public TraceMiner(DataSource dataSource, String tableName, int batchSize,
                      Path workspaceRoot, FingerprintCalculator fingerprintCalc) {
        this.batchSize = Math.max(10, batchSize);
        this.fingerprintCalc = fingerprintCalc;
        ObjectMapper objectMapper = new ObjectMapper();
        this.repository = new TraceMinerRepository(dataSource, tableName, objectMapper);
        this.l2Reader = workspaceRoot != null
                ? new L2TraceReader(workspaceRoot, objectMapper)
                : null;
    }

    /**
     * Mine all finished sessions within the given date range and upsert aggregated traces into
     * {@code user_trace_summary}.
     *
     * <p>When {@code userId} is non-blank, only sessions whose {@code session_id} starts with
     * {@code "user:&lt;userId&gt;"} are loaded. Otherwise all user-scoped sessions are mined and
     * each trace group is upserted with the extracted userId parsed from the session_id prefix.
     *
     * @param date   the target date (usually yesterday or today)
     * @param userId optional - when non-blank, filter to this user's sessions only
     * @return number of fingerprint groups upserted
     */
    public int mineTraces(LocalDate date, String userId) {
        repository.ensureUserTraceSummaryTable();
        List<TraceMinerTypes.RawSession> sessions = repository.loadSessions(date, userId, this::buildSession);
        if (sessions.isEmpty()) {
            log.debug("TraceMiner: no sessions found for {} (userId={})", date, userId);
            return 0;
        }
        // Group by (userId, fingerprint) so each user's traces are upserted under their own id
        Map<String, Map<String, TraceMinerTypes.TraceGroup>> perUser = new LinkedHashMap<>();
        for (TraceMinerTypes.RawSession s : sessions) {
            String uid = TraceMinerRepository.extractUserId(s.sessionId());
            if (uid == null) continue;
            perUser.computeIfAbsent(uid, k -> new LinkedHashMap<>());
            String fp = fingerprint(s.toolIds());
            TraceMinerTypes.TraceGroup group = perUser.get(uid).computeIfAbsent(fp, k -> new TraceMinerTypes.TraceGroup(fp, s.toolIds()));
            group.add(s);
            // Compute runtime fingerprint from userQuery (only once per group)
            if (group.runtimeFingerprint == null && s.userQuery() != null && !s.userQuery().isBlank()
                    && fingerprintCalc != null) {
                group.runtimeFingerprint = fingerprintCalc.computeMetric("query", s.userQuery());
            }
        }
        int upserted = 0;
        for (Map.Entry<String, Map<String, TraceMinerTypes.TraceGroup>> entry : perUser.entrySet()) {
            upserted += repository.upsertGroups(entry.getValue(), date, entry.getKey());
        }
        if (upserted > 0) {
            log.info("TraceMiner: mined {} fingerprint group(s) from {} session(s) for {} ({} user(s))",
                    upserted, sessions.size(), date, perUser.size());
        }
        return upserted;
    }

    /** Backwards-compatible overload - mines all user-scoped sessions. */
    public int mineTraces(LocalDate date) {
        return mineTraces(date, null);
    }

    /**
     * Build a {@link TraceMinerTypes.RawSession} from the message list. Pattern-matches
     * assistant text and TOOL messages to extract tool IDs, subagent spawn info, and
     * tool-call details. Returns null if no tool calls were detected.
     */
    private TraceMinerTypes.RawSession buildSession(String sessionId, List<TraceMinerTypes.RawMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return null;
        boolean hasToolCall = false;
        List<String> toolIds = new ArrayList<>();
        List<TraceMinerTypes.ToolCallDetail> details = new ArrayList<>();
        String lastAssistant = null;
        String userQuery = null;
        // L2: collect all agent_spawn subAgentId + subSessionId pairs
        List<TraceMinerTypes.SubAgentSpawn> subAgents = new ArrayList<>();

        for (TraceMinerTypes.RawMessage m : msgs) {
            if ("USER".equals(m.role())) {
                // Capture the first user question as the complete user query
                if (userQuery == null) {
                    userQuery = TraceMinerTypes.Truncation.truncate(m.content(), INPUT_MAX_LEN);
                }
            }
            if ("ASSISTANT".equals(m.role())) {
                lastAssistant = m.content();
                Matcher tm = TOOL_ROUTER_CALL.matcher(m.content());
                while (tm.find()) {
                    toolIds.add(tm.group(1));
                    hasToolCall = true;
                }
                if (!hasToolCall) {
                    Matcher sd = SUBAGENT_DISPATCH.matcher(m.content());
                    if (sd.find()) {
                        toolIds.add(sd.group(1));
                        hasToolCall = true;
                    }
                }
                if (!hasToolCall) {
                    Matcher sk = SKILL_NAME_IN_TEXT.matcher(m.content());
                    if (sk.find()) {
                        toolIds.add(sk.group());
                        hasToolCall = true;
                    }
                }
            }
            if ("TOOL".equals(m.role())) {
                // Try [TOOL: agent_spawn] bracket format first - extract subagent info
                if (AGENT_SPAWN_HEADER.matcher(m.content()).find()) {
                    Matcher agentMatcher = AGENT_SPAWN_FIELD.matcher(m.content());
                    Matcher sessionMatcher = SUB_SESSION_FIELD.matcher(m.content());
                    String agentId = null;
                    if (agentMatcher.find() && sessionMatcher.find()) {
                        agentId = agentMatcher.group(1);
                        subAgents.add(new TraceMinerTypes.SubAgentSpawn(agentId, sessionMatcher.group(1)));
                    }
                    if (!toolIds.contains("agent_spawn")) {
                        toolIds.add("agent_spawn");
                    }
                    hasToolCall = true;
                    // Extract input/output for agent_spawn
                    String input = "派单给 " + (agentId != null ? agentId : "unknown");
                    String output = m.content().contains("status: ok")
                            ? "status=ok"
                            : m.content().contains("error") || m.content().contains("Exception")
                                ? "status=error: " + TraceMinerTypes.Truncation.truncate(m.content(), OUTPUT_MAX_LEN)
                                : "status=unknown";
                    details.add(new TraceMinerTypes.ToolCallDetail("agent_spawn", "L1", input, output));
                }
                // Try tool_router(...) pattern
                Matcher tm = TOOL_ROUTER_CALL.matcher(m.content());
                while (tm.find()) {
                    toolIds.add(tm.group(1));
                    hasToolCall = true;
                }
                // Try broader pattern: JSON-like tool call
                if (!hasToolCall) {
                    Matcher broad = BROAD_TOOL_CALL.matcher(m.content());
                    if (broad.find()) {
                        toolIds.add(broad.group(2));
                        hasToolCall = true;
                    }
                }
                // Try [TOOL: tool_name] bracket format
                if (!hasToolCall) {
                    Matcher bracket = TOOL_BRACKET_PATTERN.matcher(m.content());
                    if (bracket.find()) {
                        String toolName = bracket.group(1);
                        toolIds.add(toolName);
                        hasToolCall = true;
                        // For non-agent_spawn L1 tools, extract output from the raw content
                        details.add(new TraceMinerTypes.ToolCallDetail(toolName, "L1", "",
                                TraceMinerTypes.Truncation.truncate(m.content(), OUTPUT_MAX_LEN)));
                    }
                }
            }
        }

        // L2: read subagent memory_messages.jsonl for deeper tool call details
        if (!subAgents.isEmpty() && l2Reader != null) {
            TraceMinerTypes.ParseL2Result l2Result = l2Reader.readSubAgentToolCalls(subAgents);
            if (!l2Result.toolNames().isEmpty()) {
                // Merge L2 tool IDs between L1 entries (after the last agent_spawn)
                int lastSpawnIdx = -1;
                for (int i = toolIds.size() - 1; i >= 0; i--) {
                    if ("agent_spawn".equals(toolIds.get(i))) {
                        lastSpawnIdx = i;
                        break;
                    }
                }
                if (lastSpawnIdx >= 0) {
                    toolIds.addAll(lastSpawnIdx + 1, l2Result.toolNames());
                } else {
                    toolIds.addAll(l2Result.toolNames());
                }
                details.addAll(l2Result.details());
                hasToolCall = true;
            }
        }

        if (!hasToolCall) return null;

        // Classify failure based on tool results and overall session health
        TraceMinerTypes.FailureClass fc = classifyTrace(msgs);
        boolean sessionSuccessful = fc.successful();

        return new TraceMinerTypes.RawSession(sessionId, toolIds, fc.failureCount(), fc.failureScore(),
                lastAssistant != null ? TraceMinerTypes.Truncation.truncate(lastAssistant, SNIPPET_MAX_LEN) : "",
                sessionSuccessful,
                userQuery,
                details);
    }

    /**
     * Derive a fingerprint from the tool call sequence. This yields a coarser grouping than the
     * intent|dimensionKey fingerprint used by {@code SkillSynthesisHook}, but it's reliably
     * computable from the episodic trace alone.
     */
    static String fingerprint(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) return "_no_tool";
        return String.join("|", toolIds);
    }

    /**
     * Classify a session as success, failure, or possible failure based on tool results,
     * error signals, and session completion markers. Returns a FailureClass with score
     * and the successful flag used for trace aggregation.
     *
     * <p>A session is considered successful when:
     * <ul>
     *   <li>agent_spawn returned status=ok (not error/Exception in TOOL content)</li>
     *   <li>No explicit error keywords in tool outputs</li>
     *   <li>Contains a finish or completion marker, OR the conversation ended naturally</li>
     * </ul>
     */
    private TraceMinerTypes.FailureClass classifyTrace(List<TraceMinerTypes.RawMessage> msgs) {
        boolean hasExplicitFailure = false;
        boolean hasToolResult = false;
        double score = 0.0;
        int failCount = 0;

        for (TraceMinerTypes.RawMessage m : msgs) {
            if ("TOOL".equals(m.role())) {
                hasToolResult = true;
                // Check for explicit error/exception in tool output
                if (ERROR_PATTERN.matcher(m.content()).find()) {
                    hasExplicitFailure = true;
                    score += FAILURE_WEIGHT;
                    failCount++;
                }
                // Check for non-zero exit code
                if (EXIT_CODE_PATTERN.matcher(m.content()).find()) {
                    hasExplicitFailure = true;
                    score += FAILURE_WEIGHT;
                    failCount++;
                }
                // agent_spawn with status=ok is a success signal
                if (AGENT_SPAWN_HEADER.matcher(m.content()).find()
                        && m.content().contains("status: ok")) {
                    // This is a good spawn, don't penalize
                }
            }
            if ("ASSISTANT".equals(m.role())) {
                if (MAX_ITERS_PATTERN.matcher(m.content()).find()) {
                    hasExplicitFailure = true;
                    score += POSSIBLE_FAILURE_WEIGHT;
                }
            }
        }

        // If there was an explicit failure signal, mark as failed
        if (hasExplicitFailure) {
            return new TraceMinerTypes.FailureClass(failCount, score, false);
        }

        // If we have tool results and no explicit failure, the session succeeded
        if (hasToolResult) {
            return new TraceMinerTypes.FailureClass(0, 0.0, true);
        }

        // No tool results found - cannot determine success
        return new TraceMinerTypes.FailureClass(0, score, false);
    }

    /**
     * Deserialise tool call details from JSON stored in user_trace_summary.tool_call_details.
     * Delegated to repository for backward compatibility with callers that used the old
     * static method on TraceMiner.
     */
    public static List<TraceMinerTypes.ToolCallDetail> fromToolCallDetailsJson(String json) {
        return TraceMinerRepository.fromToolCallDetailsJson(json);
    }
}
