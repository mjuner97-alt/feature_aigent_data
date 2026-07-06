/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.agent.memory.digestion;

import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Offline tool-call trace miner that extracts per-session tool invocation sequences from
 * {@code episodic_memory} and writes the aggregated summary to {@code user_trace_summary}.
 *
 * <p>Runs in the nightly digestion pipeline ({@link MemoryDigestionService}, Phase 2).
 * Operates on the JDBC {@link DataSource} directly (not the {@link MySqlEpisodicMemory} API)
 * so we can batch-scan large session ranges without loading every row through the reactive
 * wrapper.
 *
 * <h3>Failure classification</h3>
 * Each trace row is classified via {@link #classifyTrace}:
 * <ul>
 *   <li>{@code FAILURE} (weight 1.0) — python_exec non-zero exit, content contains "error" /
 *       "Exception" / "失败"</li>
 *   <li>{@code FAILURE} (weight 1.0) — tool_router returned a markdown table with only headers
 *       and no data rows (empty query result)</li>
 *   <li>{@code POSSIBLE_FAILURE} (weight 0.5) — session ended without a finish marker, or
 *       the last assistant message suggests a timeout / maxIters exhaustion</li>
 * </ul>
 */
public class TraceMiner {

    private static final Logger log = LoggerFactory.getLogger(TraceMiner.class);

    /** Session prefix injected by HarnessA2aRunner.recordCacheHitToEpisodic — skip these. */
    private static final String CACHE_HIT_PREFIX = "cache-hit:";

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

    private final DataSource dataSource;
    private final String tableName;
    private final int batchSize;
    private final Path workspaceRoot;
    private final ObjectMapper objectMapper;

    /**
     * @param dataSource the JDBC DataSource (same pool used by MySqlEpisodicMemory)
     * @param tableName  the episodic memory table name (e.g. {@code QualitySupervisor_episodic_memory})
     * @param batchSize  max rows per batch query
     */
    public TraceMiner(DataSource dataSource, String tableName, int batchSize) {
        this(dataSource, tableName, batchSize, null);
    }

    /**
     * Full constructor with workspace root for L2 subagent memory file reading.
     *
     * @param dataSource     the JDBC DataSource
     * @param tableName      the episodic memory table name
     * @param batchSize      max rows per batch query
     * @param workspaceRoot  the harness workspace root (e.g. {@code .agentscope/workspace/harness-a2a}),
     *                       or {@code null} to skip L2 file reading
     */
    public TraceMiner(DataSource dataSource, String tableName, int batchSize, Path workspaceRoot) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.batchSize = Math.max(10, batchSize);
        this.workspaceRoot = workspaceRoot;
        this.objectMapper = new ObjectMapper();
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
     * @param userId optional — when non-blank, filter to this user's sessions only
     * @return number of fingerprint groups upserted
     */
    public int mineTraces(LocalDate date, String userId) {
        ensureUserTraceSummaryTable();
        List<RawSession> sessions = loadSessions(date, userId);
        if (sessions.isEmpty()) {
            log.debug("TraceMiner: no sessions found for {} (userId={})", date, userId);
            return 0;
        }
        // Group by (userId, fingerprint) so each user's traces are upserted under their own id
        Map<String, Map<String, TraceGroup>> perUser = new LinkedHashMap<>();
        for (RawSession s : sessions) {
            String uid = extractUserId(s.sessionId);
            if (uid == null) continue;
            perUser.computeIfAbsent(uid, k -> new LinkedHashMap<>());
            String fp = fingerprint(s.toolIds);
            perUser.get(uid).computeIfAbsent(fp, k -> new TraceGroup(fp, s.toolIds)).add(s);
        }
        int upserted = 0;
        for (Map.Entry<String, Map<String, TraceGroup>> entry : perUser.entrySet()) {
            upserted += upsertGroups(entry.getValue(), date, entry.getKey());
        }
        if (upserted > 0) {
            log.info("TraceMiner: mined {} fingerprint group(s) from {} session(s) for {} ({} user(s))",
                    upserted, sessions.size(), date, perUser.size());
        }
        return upserted;
    }

    /** Backwards-compatible overload — mines all user-scoped sessions. */
    public int mineTraces(LocalDate date) {
        return mineTraces(date, null);
    }

    /** Prefixes used by {@code SupervisorService.ltmBucketFor()} to encode user/session scope. */
    private static final String USER_PREFIX = "user:";
    private static final String SESSION_PREFIX = "session:";

    /**
     * Extract the real user id from a session_id produced by
     * {@code SupervisorService.ltmBucketFor()}.
     *
     * <ul>
     *   <li>{@code "user:u-1024"} → {@code "u-1024"}
     *   <li>{@code "session:abc"}  → {@code null} (session-scoped, no user identity)
     *   <li>{@code "anonymous"}    → {@code null}
     *   <li>{@code "cache-hit:..."} → {@code null}
     *   <li>anything else          → {@code null}
     * </ul>
     */
    static String extractUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (sessionId.startsWith(USER_PREFIX)) {
            String uid = sessionId.substring(USER_PREFIX.length());
            return uid.isBlank() ? null : uid;
        }
        return null;
    }

    /**
     * Load non-cache-hit, finished (or finished-looking) sessions from the episodic memory table
     * for the given date. When {@code userId} is non-blank, filters to sessions whose
     * {@code session_id} starts with {@code "user:&lt;userId&gt;"}.
     */
    private List<RawSession> loadSessions(LocalDate date, String userId) {
        StringBuilder sqlSb = new StringBuilder(
                "SELECT id, session_id, role, content, created_at FROM ")
                .append(tableName)
                .append(" WHERE DATE(created_at) = ?")
                .append(" AND session_id NOT LIKE ?")
                .append(" AND role IN ('USER', 'ASSISTANT', 'TOOL')");
        // Filter by userId when specified
        if (userId != null && !userId.isBlank()) {
            sqlSb.append(" AND session_id = ?");
        }
        sqlSb.append(" ORDER BY session_id, id ASC");
        String sql = sqlSb.toString();

        Map<String, List<RawMessage>> grouped = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.setString(2, CACHE_HIT_PREFIX + "%");
            if (userId != null && !userId.isBlank()) {
                ps.setString(3, USER_PREFIX + userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("session_id");
                    String role = rs.getString("role");
                    String content = rs.getString("content");
                    if (content == null || content.isBlank()) continue;
                    grouped.computeIfAbsent(sid, k -> new ArrayList<>())
                            .add(new RawMessage(role, content));
                }
            }
        } catch (SQLException e) {
            log.warn("TraceMiner.loadSessions({}, {}) failed: {}", date, userId, e.getMessage());
            return List.of();
        }
        return grouped.entrySet().stream()
                .map(e -> buildSession(e.getKey(), e.getValue()))
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    private RawSession buildSession(String sessionId, List<RawMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return null;
        boolean hasToolCall = false;
        List<String> toolIds = new ArrayList<>();
        List<ToolCallDetail> details = new ArrayList<>();
        String lastAssistant = null;
        String userQuery = null;
        // L2: collect all agent_spawn subAgentId + subSessionId pairs
        List<SubAgentSpawn> subAgents = new ArrayList<>();

        for (RawMessage m : msgs) {
            if ("USER".equals(m.role)) {
                // Capture the first user question as the complete user query
                if (userQuery == null) {
                    userQuery = truncate(m.content, INPUT_MAX_LEN);
                }
            }
            if ("ASSISTANT".equals(m.role)) {
                lastAssistant = m.content;
                java.util.regex.Matcher tm = TOOL_ROUTER_CALL.matcher(m.content);
                while (tm.find()) {
                    toolIds.add(tm.group(1));
                    hasToolCall = true;
                }
                if (!hasToolCall) {
                    java.util.regex.Matcher sd = SUBAGENT_DISPATCH.matcher(m.content);
                    if (sd.find()) {
                        toolIds.add(sd.group(1));
                        hasToolCall = true;
                    }
                }
                if (!hasToolCall) {
                    java.util.regex.Matcher sk = SKILL_NAME_IN_TEXT.matcher(m.content);
                    if (sk.find()) {
                        toolIds.add(sk.group());
                        hasToolCall = true;
                    }
                }
            }
            if ("TOOL".equals(m.role)) {
                // Try [TOOL: agent_spawn] bracket format first — extract subagent info
                if (AGENT_SPAWN_HEADER.matcher(m.content).find()) {
                    java.util.regex.Matcher agentMatcher = AGENT_SPAWN_FIELD.matcher(m.content);
                    java.util.regex.Matcher sessionMatcher = SUB_SESSION_FIELD.matcher(m.content);
                    String agentId = null;
                    if (agentMatcher.find() && sessionMatcher.find()) {
                        agentId = agentMatcher.group(1);
                        subAgents.add(new SubAgentSpawn(agentId, sessionMatcher.group(1)));
                    }
                    if (!toolIds.contains("agent_spawn")) {
                        toolIds.add("agent_spawn");
                    }
                    hasToolCall = true;
                    // Extract input/output for agent_spawn
                    String input = "派单给 " + (agentId != null ? agentId : "unknown");
                    String output = m.content.contains("status: ok")
                            ? "status=ok"
                            : m.content.contains("error") || m.content.contains("Exception")
                                ? "status=error: " + truncate(m.content, OUTPUT_MAX_LEN)
                                : "status=unknown";
                    details.add(new ToolCallDetail("agent_spawn", "L1", input, output));
                }
                // Try tool_router(...) pattern
                java.util.regex.Matcher tm = TOOL_ROUTER_CALL.matcher(m.content);
                while (tm.find()) {
                    toolIds.add(tm.group(1));
                    hasToolCall = true;
                }
                // Try broader pattern: JSON-like tool call
                if (!hasToolCall) {
                    java.util.regex.Matcher broad = BROAD_TOOL_CALL.matcher(m.content);
                    if (broad.find()) {
                        toolIds.add(broad.group(2));
                        hasToolCall = true;
                    }
                }
                // Try [TOOL: tool_name] bracket format
                if (!hasToolCall) {
                    java.util.regex.Matcher bracket = TOOL_BRACKET_PATTERN.matcher(m.content);
                    if (bracket.find()) {
                        String toolName = bracket.group(1);
                        toolIds.add(toolName);
                        hasToolCall = true;
                        // For non-agent_spawn L1 tools, extract output from the raw content
                        details.add(new ToolCallDetail(toolName, "L1", "",
                                truncate(m.content, OUTPUT_MAX_LEN)));
                    }
                }
            }
        }

        // L2: read subagent memory_messages.jsonl for deeper tool call details
        if (!subAgents.isEmpty() && workspaceRoot != null) {
            ParseL2Result l2Result = readSubAgentToolCalls(subAgents);
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
        FailureClass fc = classifyTrace(msgs);
        boolean sessionSuccessful = fc.successful;

        return new RawSession(sessionId, toolIds, fc.failureCount, fc.failureScore,
                lastAssistant != null ? truncate(lastAssistant, SNIPPET_MAX_LEN) : "",
                sessionSuccessful,
                userQuery,
                details);
    }

    /**
     * Group sessions by fingerprint (inferred from the tool sequence). Sessions with identical
     * tool call sequences are grouped together as the same "fingerprint" for aggregation.
     *
     * @deprecated replaced by per-user grouping in {@link #mineTraces(LocalDate, String)}
     */
    @Deprecated
    private Map<String, TraceGroup> aggregate(List<RawSession> sessions) {
        Map<String, TraceGroup> groups = new LinkedHashMap<>();
        for (RawSession s : sessions) {
            String fp = fingerprint(s.toolIds);
            groups.computeIfAbsent(fp, k -> new TraceGroup(fp, s.toolIds))
                    .add(s);
        }
        return groups;
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

    private int upsertGroups(Map<String, TraceGroup> groups, LocalDate date, String userId) {
        String sql = "INSERT INTO user_trace_summary"
                + " (user_id, date_key, fingerprint, tool_sequence, success_count, failure_count,"
                + "  sample_query, user_query, tool_call_details, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')"
                + " ON DUPLICATE KEY UPDATE"
                + "   success_count = VALUES(success_count),"
                + "   failure_count = VALUES(failure_count),"
                + "   sample_query = VALUES(sample_query),"
                + "   user_query = VALUES(user_query),"
                + "   tool_call_details = VALUES(tool_call_details),"
                + "   tool_sequence = VALUES(tool_sequence),"
                + "   status = CASE WHEN status = 'pending' THEN 'pending' ELSE status END";
        int count = 0;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (TraceGroup g : groups.values()) {
                // Count actual successful vs failed sessions from per-session classification
                int totalSuccess = g.totalSuccess;
                int totalFailure = g.sessions.size() - g.totalSuccess;
                // Ensure at least 1 failure count so status stays pending for review
                totalFailure = Math.max(totalFailure, g.totalFailureCount > 0 ? 1 : 0);
                ps.setString(1, userId != null ? userId : "_batch");
                ps.setString(2, date.toString());
                ps.setString(3, g.fingerprint);
                ps.setString(4, String.join(",", g.toolSequence));
                ps.setInt(5, Math.max(0, totalSuccess));
                ps.setInt(6, Math.max(1, totalFailure));
                ps.setString(7, g.sampleQuery());
                ps.setString(8, g.userQuery != null ? g.userQuery : "");
                ps.setString(9, toToolCallDetailsJson(g.details));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("TraceMiner.upsertGroups failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Ensure the {@code user_trace_summary} and {@code digestion_log} tables exist.
     * Also migrates existing tables with ALTER TABLE ADD COLUMN (idempotent).
     */
    private void ensureUserTraceSummaryTable() {
        String traceDdl = "CREATE TABLE IF NOT EXISTS user_trace_summary ("
                + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "  user_id VARCHAR(128) NOT NULL,"
                + "  date_key VARCHAR(16) NOT NULL,"
                + "  fingerprint VARCHAR(255) NOT NULL,"
                + "  tool_sequence TEXT NOT NULL,"
                + "  success_count INT NOT NULL DEFAULT 0,"
                + "  failure_count INT NOT NULL DEFAULT 0,"
                + "  failure_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,"
                + "  sample_query TEXT,"
                + "  user_query TEXT,"
                + "  tool_call_details LONGTEXT,"
                + "  status VARCHAR(16) DEFAULT 'pending',"
                + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "  UNIQUE KEY uk_user_date_fp (user_id, date_key, fingerprint),"
                + "  KEY idx_user_date (user_id, date_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        String logDdl = "CREATE TABLE IF NOT EXISTS digestion_log ("
                + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "  user_id VARCHAR(128) NOT NULL,"
                + "  date_key VARCHAR(16) NOT NULL,"
                + "  phase1_cleaned_ledger INT DEFAULT 0,"
                + "  phase2_mined_traces INT DEFAULT 0,"
                + "  phase3_skills_evolved INT DEFAULT 0,"
                + "  phase4_memory_digested TINYINT(1) DEFAULT 0,"
                + "  started_at TIMESTAMP NOT NULL,"
                + "  completed_at TIMESTAMP NULL,"
                + "  error_msg TEXT,"
                + "  KEY idx_user_date (user_id, date_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute(traceDdl);
            st.execute(logDdl);
            // Idempotent ALTER TABLE for legacy tables that lack the new columns
            String[] alterSqls = {
                "ALTER TABLE user_trace_summary ADD COLUMN user_query TEXT AFTER sample_query",
                "ALTER TABLE user_trace_summary ADD COLUMN tool_call_details LONGTEXT AFTER user_query"
            };
            for (String sql : alterSqls) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    // Column already exists — ignore
                }
            }
        } catch (SQLException e) {
            log.warn("TraceMiner DDL failed: {}", e.getMessage());
        }
    }

    /**
     * Read subagent memory_messages.jsonl files and extract L2 tool call names + details.
     * Each subagent spawn (detected by AGENT_SPAWN_INFO) maps to a file at:
     *   workspace/agents/{agentId}/context/sub-{subSessionId}/memory_messages.jsonl
     *
     * <p>Returns deduplicated tool names preserving insertion order + full detail records.
     * Returns empty ParseL2Result if all files are missing/unreadable (graceful degradation to L1-only).
     */
    private ParseL2Result readSubAgentToolCalls(List<SubAgentSpawn> spawns) {
        List<String> allToolNames = new ArrayList<>();
        List<ToolCallDetail> allDetails = new ArrayList<>();
        for (SubAgentSpawn spawn : spawns) {
            Path l2File = workspaceRoot
                    .resolve("agents")
                    .resolve(spawn.agentId)
                    .resolve("context")
                    .resolve(spawn.subSessionId)
                    .resolve("memory_messages.jsonl");
            if (!Files.exists(l2File)) {
                log.debug("L2 file not found (graceful degradation to L1): {}", l2File);
                continue;
            }
            try {
                ParseL2Result result = parseL2ToolCalls(l2File, spawn.agentId);
                allToolNames.addAll(result.toolNames());
                allDetails.addAll(result.details());
                log.debug("L2: extracted {} tool(s) + {} detail(s) from {}",
                        result.toolNames().size(), result.details().size(), l2File);
            } catch (Exception e) {
                log.debug("L2 file read failed (graceful degradation to L1): {} - {}",
                        l2File, e.getMessage());
            }
        }
        List<String> distinct = allToolNames.stream().distinct().collect(Collectors.toList());
        return new ParseL2Result(distinct, allDetails);
    }

    /**
     * Parse a single memory_messages.jsonl file, extracting tool_use and tool_result names
     * along with their input/output context.
     *
     * <p>Each line is a JSON message like:
     * <pre>
     * {"role":"ASSISTANT","content":[{"type":"tool_use","name":"toolMetaInfo","input":{...}}]}
     * {"role":"TOOL","content":[{"type":"tool_result","name":"toolMetaInfo","output":[...]}]}
     * </pre>
     *
     * <p>Input is kept up to {@link #INPUT_MAX_LEN} chars, output to {@link #OUTPUT_MAX_LEN}.
     */
    private ParseL2Result parseL2ToolCalls(Path l2File, String agentId) throws IOException {
        List<String> tools = new ArrayList<>();
        List<ToolCallDetail> details = new ArrayList<>();
        List<String> lines = Files.readAllLines(l2File);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                JsonNode content = node.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    JsonNode type = block.get("type");
                    if (type == null) continue;
                    String typeStr = type.asText();
                    JsonNode name = block.get("name");
                    if (name == null || name.asText().isBlank()) continue;
                    String toolName = name.asText();
                    tools.add(toolName);

                    String input = "";
                    String output = "";
                    if ("tool_use".equals(typeStr)) {
                        JsonNode inputNode = block.get("input");
                        if (inputNode != null) {
                            input = truncate(inputNode.toString(), INPUT_MAX_LEN);
                        }
                    } else if ("tool_result".equals(typeStr)) {
                        JsonNode outputNode = block.get("output");
                        if (outputNode != null) {
                            // Only keep the first line of tool results — full output (e.g. entire
                            // SKILL.md content from load_skill_through_path) drowns out the tool
                            // name and input params that the LLM actually needs to reconstruct the
                            // correct tool chain. A one-line status signal is sufficient.
                            String raw = outputNode.toString();
                            int nl = raw.indexOf('\n');
                            output = nl > 0 ? raw.substring(0, nl) + " …" : truncate(raw, 160);
                        }
                    }
                    details.add(new ToolCallDetail(toolName, "L2", input, output));
                }
            } catch (IOException e) {
                log.debug("parseL2ToolCalls: skipping malformed line: {}", e.getMessage());
            }
        }
        return new ParseL2Result(tools, details);
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
    private FailureClass classifyTrace(List<RawMessage> msgs) {
        boolean hasExplicitFailure = false;
        boolean hasToolResult = false;
        double score = 0.0;
        int failCount = 0;

        for (RawMessage m : msgs) {
            if ("TOOL".equals(m.role)) {
                hasToolResult = true;
                // Check for explicit error/exception in tool output
                if (ERROR_PATTERN.matcher(m.content).find()) {
                    hasExplicitFailure = true;
                    score += FAILURE_WEIGHT;
                    failCount++;
                }
                // Check for non-zero exit code
                if (EXIT_CODE_PATTERN.matcher(m.content).find()) {
                    hasExplicitFailure = true;
                    score += FAILURE_WEIGHT;
                    failCount++;
                }
                // agent_spawn with status=ok is a success signal
                if (AGENT_SPAWN_HEADER.matcher(m.content).find()
                        && m.content.contains("status: ok")) {
                    // This is a good spawn, don't penalize
                }
            }
            if ("ASSISTANT".equals(m.role)) {
                if (MAX_ITERS_PATTERN.matcher(m.content).find()) {
                    hasExplicitFailure = true;
                    score += POSSIBLE_FAILURE_WEIGHT;
                }
            }
        }

        // If there was an explicit failure signal, mark as failed
        if (hasExplicitFailure) {
            return new FailureClass(failCount, score, false);
        }

        // If we have tool results and no explicit failure, the session succeeded
        if (hasToolResult) {
            return new FailureClass(0, 0.0, true);
        }

        // No tool results found — cannot determine success
        return new FailureClass(0, score, false);
    }

    record FailureClass(int failureCount, double failureScore, boolean successful) {}

    record RawMessage(String role, String content) {}

    record RawSession(String sessionId, List<String> toolIds,
                      int failureCount, double failureScore, String lastSnippet,
                      boolean successful,
                      String userQuery, List<ToolCallDetail> details) {}

    /** L2 subagent spawn info extracted from TOOL agent_spawn message. */
    record SubAgentSpawn(String agentId, String subSessionId) {}

    /**
     * Tool call detail with input/output context for skill distillation.
     * Output is truncated to {@link #OUTPUT_MAX_LEN} chars — only the result shape matters,
     * not the actual data values. Input is kept up to {@link #INPUT_MAX_LEN}.
     */
    record ToolCallDetail(String tool, String level, String input, String output) {}

    /** Result of parsing a single L2 memory_messages.jsonl file. */
    record ParseL2Result(List<String> toolNames, List<ToolCallDetail> details) {
        static ParseL2Result empty() {
            return new ParseL2Result(List.of(), List.of());
        }
    }

    /**
     * Serialise tool call details to JSON for storage in user_trace_summary.tool_call_details.
     */
    private String toToolCallDetailsJson(List<ToolCallDetail> details) {
        if (details == null || details.isEmpty()) return "";
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.debug("toToolCallDetailsJson failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Deserialise tool call details from JSON stored in user_trace_summary.tool_call_details.
     */
    static List<ToolCallDetail> fromToolCallDetailsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ToolCallDetail>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    static class TraceGroup {
        final String fingerprint;
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
            return truncate(sampleQuery, 500);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
