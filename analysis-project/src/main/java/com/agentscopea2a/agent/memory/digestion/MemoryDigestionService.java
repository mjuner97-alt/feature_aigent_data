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

import com.agentscopea2a.agent.memory.MemoryHydrator;
import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import com.agentscopea2a.agent.memory.MysqlMemoryStore;
import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
import com.agentscopea2a.harness.skills.SkillDistiller;
import com.agentscopea2a.harness.skills.SkillIndexRepository;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
import io.agentscope.core.model.Model;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Nightly memory digestion scheduler.
 *
 * <p>Runs once per night at the configured cron expression (default 21:09) and orchestrates four
 * phases for each active user:
 *
 * <ol>
 *   <li><b>CleanLedger</b> — purge {@code agent_memory_ledger} rows older than
 *       {@code ledger-retention-days} (default 90).</li>
 *   <li><b>MineTraces</b> — scan {@code episodic_memory} for the current day, extract tool-call
 *       sequences, classify failures, and upsert to {@code user_trace_summary}.</li>
 *   <li><b>EvolveSkills</b> — evaluate per-fingerprint failure rates and dispatch skill
 *       evolution/distillation for high-failure patterns.</li>
 *   <li><b>ConsolidateMemory</b> — merge today's successful traces into the per-user MEMORY.md.</li>
 * </ol>
 *
 * <p>Uses MySQL {@code GET_LOCK} for cross-JVM mutual exclusion so only one replica runs the
 * digestion. Each phase is independently try-catch guarded so a single phase failure doesn't
 * block the entire pipeline. User-level errors are also isolated — one user's failure doesn't
 * affect others.
 *
 * <p>Bean condition: {@code harness.a2a.memory.digestion.enabled=true}
 */
@Component
@ConditionalOnProperty(
        prefix = "harness.a2a.memory.digestion",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class MemoryDigestionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryDigestionService.class);

    /** Lock name for cross-JVM mutual exclusion. */
    private static final String DIGEST_LOCK = "memory_digestion_lock";

    private final DataSource dataSource;
    private final MysqlMemoryStore store;
    private final MemoryHydrator hydrator;
    private final ObjectProvider<Model> modelProvider;
    private final SkillIndexRepository indexRepo;
    private final SkillVectorIndex vectorIndex;
    private final SkillDistiller distiller;
    private final EmbeddingClient embeddingClient;
    private final SkillFlowEvolver evolver;
    private final FingerprintCalculator fingerprintCalc;
    private final String cronExpression;
    private final int batchSize;
    private final int episodicRetentionDays;
    private final int ledgerRetentionDays;
    private final int summaryMaxLength;
    private final String episodicTableName;
    private final Path workspaceMemoryRoot;
    private final Path workspaceRoot;
    private final boolean enabled;

    public MemoryDigestionService(
            @Qualifier("mysqlDataSource") DataSource dataSource,
            MysqlMemoryStore store,
            MemoryHydrator hydrator,
            ObjectProvider<Model> modelProvider,
            SkillIndexRepository indexRepo,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            SkillDistiller distiller,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<SkillFlowEvolver> evolverProvider,
            FingerprintCalculator fingerprintCalc,
            @Value("${harness.a2a.memory.digestion.cron:0 9 21 * * *}") String cronExpression,
            @Value("${harness.a2a.memory.digestion.batch-size:50}") int batchSize,
            @Value("${harness.a2a.memory.digestion.episodic-retention-days:30}") int episodicRetentionDays,
            @Value("${harness.a2a.memory.digestion.ledger-retention-days:90}") int ledgerRetentionDays,
            @Value("${harness.a2a.memory.digestion.summary-max-length:200}") int summaryMaxLength,
            @Value("${harness.a2a.memory.digestion.enabled:false}") boolean enabled,
            @Value("${harness.a2a.memory.digestion.episodic-table-name:QualitySupervisor_episodic_memory}")
                    String episodicTableName,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspaceRoot) {
        this.dataSource = dataSource;
        this.store = store;
        this.hydrator = hydrator;
        this.modelProvider = modelProvider;
        this.indexRepo = indexRepo;
        this.vectorIndex = vectorIndexProvider.getIfAvailable();
        this.distiller = distiller;
        this.embeddingClient = embeddingClientProvider.getIfAvailable();
        this.evolver = evolverProvider.getIfAvailable();
        this.fingerprintCalc = fingerprintCalc;
        this.cronExpression = cronExpression;
        this.batchSize = batchSize;
        this.episodicRetentionDays = episodicRetentionDays;
        this.ledgerRetentionDays = ledgerRetentionDays;
        this.summaryMaxLength = summaryMaxLength;
        this.episodicTableName = episodicTableName;
        this.workspaceMemoryRoot = java.nio.file.Path.of(workspaceRoot).resolve("memory");
        this.workspaceRoot = java.nio.file.Path.of(workspaceRoot);
        this.enabled = enabled;
    }

    @PostConstruct
    void announce() {
        if (enabled) {
            log.info("MemoryDigestionService enabled — cron={}, episodicRetention={}d, ledgerRetention={}d",
                    cronExpression, episodicRetentionDays, ledgerRetentionDays);
        }
    }

    /**
     * Nightly digestion entry point. Attempts to acquire a MySQL named lock; if another replica
     * already holds it, this instance skips execution silently.
     */
    @Scheduled(cron = "${harness.a2a.memory.digestion.cron:0 9 21 * * *}")
    public void digest() {
        if (!tryLock()) {
            log.debug("MemoryDigestionService: lock held by another instance; skipping");
            return;
        }
        try {
            doDigest();
        } catch (Exception e) {
            log.error("MemoryDigestionService: uncaught error: {}", e.getMessage(), e);
        } finally {
            releaseLock();
        }
    }

    private void doDigest() {
        LocalDate today = LocalDate.now();
        List<String> activeUsers = findActiveUsers();
        log.info("MemoryDigestion: processing {} active user(s)", activeUsers.size());

        for (String userId : activeUsers) {
            long start = System.currentTimeMillis();
            try {
                digestForUser(userId, today);
                log.info("MemoryDigestion: [{}] completed in {}ms", userId,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("MemoryDigestion: [{}] failed after {}ms: {}",
                        userId, System.currentTimeMillis() - start, e.getMessage());
            }
        }
    }

    private void digestForUser(String userId, LocalDate today) {
        if (userId == null || userId.isBlank()) {
            log.warn("MemoryDigestion: skipping null/blank userId");
            return;
        }

        // Phase 1: Clean old ledger entries
        int cleaned = 0;
        try {
            LocalDate cutoff = today.minusDays(ledgerRetentionDays);
            cleaned = store.deleteLedgerBefore(userId, cutoff);
        } catch (Exception e) {
            log.warn("MemoryDigestion: [{}] Phase 1 (CleanLedger) failed: {}", userId, e.getMessage());
        }

        // Phase 2: Mine traces from episodic memory + L2 subagent files
        TraceMiner miner = new TraceMiner(dataSource, episodicTableName, batchSize, workspaceRoot, fingerprintCalc);
        int mined = 0;
        try {
            mined = miner.mineTraces(today, userId);
        } catch (Exception e) {
            log.warn("MemoryDigestion: [{}] Phase 2 (MineTraces) failed: {}", userId, e.getMessage());
        }

        // Phase 3: Evaluate and evolve skills
        int evolved = 0;
        try {
            if (evolver != null) {
                List<SkillFlowEvolver.TraceSummary> pendingTraces = loadPendingTraces(userId, today);
                if (!pendingTraces.isEmpty()) {
                    evolved = evolver.evolve(pendingTraces);
                }
            } else {
                log.warn("SkillFlowEvolver bean not available (digestion.enabled=false?); skipping Phase 3");
            }
        } catch (Exception e) {
            log.warn("MemoryDigestion: [{}] Phase 3 (EvolveSkills) failed: {}", userId, e.getMessage());
        }

        // Phase 4: Consolidate MEMORY.md
        boolean digested = false;
        try {
            List<SkillFlowEvolver.TraceSummary> successTraces = loadSuccessTraces(userId, today);
            if (!successTraces.isEmpty()) {
                MemoryFlowConsolidator consolidator =
                        new MemoryFlowConsolidator(store, hydrator, modelProvider.getIfAvailable(), workspaceMemoryRoot);
                digested = consolidator.consolidate(userId, successTraces);
            }
        } catch (Exception e) {
            log.warn("MemoryDigestion: [{}] Phase 4 (ConsolidateMemory) failed: {}", userId, e.getMessage());
        }

        // Record digestion log
        recordDigestionLog(userId, today, cleaned, mined, evolved, digested, null);
    }

    // ==================== Phase helpers ====================

    /**
     * Find active users from the ledger table for the past day. Falls back to parsing
     * {@code user:xxx} session_id prefixes from episodic_memory.
     */
    private List<String> findActiveUsers() {
        // Primary: query agent_memory_ledger for distinct user_ids
        List<String> users = store.findActiveUsers(1);
        if (!users.isEmpty()) return users;

        // Fallback: parse user:xxx prefix from episodic_memory session_ids
        String sql = "SELECT DISTINCT session_id FROM " + episodicTableName
                + " WHERE DATE(created_at) = ? AND session_id LIKE 'user:%'"
                + " AND session_id NOT LIKE 'cache-hit:%'";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, LocalDate.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("session_id");
                    if (sid != null && sid.startsWith("user:") && sid.length() > 5) {
                        users.add(sid.substring(5));
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("findActiveUsers episodic fallback failed: {}", e.getMessage());
        }
        return users;
    }

    /**
     * Load pending trace summaries from user_trace_summary for a user/date.
     * These are traces that haven't been synthesized yet (status='pending').
     */
    private List<SkillFlowEvolver.TraceSummary> loadPendingTraces(String userId, LocalDate date) {
        String sql = "SELECT fingerprint, runtime_fingerprint, tool_sequence, success_count, failure_count, sample_query,"
                + " user_query, tool_call_details"
                + " FROM user_trace_summary"
                + " WHERE user_id = ? AND date_key = ? AND status = 'pending'";
        List<SkillFlowEvolver.TraceSummary> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillFlowEvolver.TraceSummary(
                            rs.getString("fingerprint"),
                            rs.getString("runtime_fingerprint"),
                            rs.getString("tool_sequence"),
                            rs.getInt("success_count"),
                            rs.getInt("failure_count"),
                            rs.getString("sample_query"),
                            rs.getString("user_query"),
                            rs.getString("tool_call_details")));
                }
            }
        } catch (SQLException e) {
            log.warn("loadPendingTraces({}, {}) failed: {}", userId, date, e.getMessage());
        }
        return results;
    }

    /**
     * Load traces with non-zero success count for MEMORY.md consolidation.
     */
    private List<SkillFlowEvolver.TraceSummary> loadSuccessTraces(String userId, LocalDate date) {
        String sql = "SELECT fingerprint, runtime_fingerprint, tool_sequence, success_count, failure_count, sample_query,"
                + " user_query, tool_call_details"
                + " FROM user_trace_summary"
                + " WHERE user_id = ? AND date_key = ? AND success_count > 0";
        List<SkillFlowEvolver.TraceSummary> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SkillFlowEvolver.TraceSummary(
                            rs.getString("fingerprint"),
                            rs.getString("runtime_fingerprint"),
                            rs.getString("tool_sequence"),
                            rs.getInt("success_count"),
                            rs.getInt("failure_count"),
                            rs.getString("sample_query"),
                            rs.getString("user_query"),
                            rs.getString("tool_call_details")));
                }
            }
        } catch (SQLException e) {
            log.warn("loadSuccessTraces({}, {}) failed: {}", userId, date, e.getMessage());
        }
        return results;
    }

    /**
     * Record the digestion execution result into {@code digestion_log}.
     */
    private void recordDigestionLog(String userId, LocalDate date,
                                    int cleaned, int mined, int evolved,
                                    boolean digested, String errorMsg) {
        String sql = "INSERT INTO digestion_log"
                + " (user_id, date_key, phase1_cleaned_ledger, phase2_mined_traces,"
                + " phase3_skills_evolved, phase4_memory_digested, started_at, completed_at, error_msg)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, date.toString());
            ps.setInt(3, cleaned);
            ps.setInt(4, mined);
            ps.setInt(5, evolved);
            ps.setBoolean(6, digested);
            ps.setTimestamp(7, java.sql.Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(8, errorMsg);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordDigestionLog({}, {}) failed: {}", userId, date, e.getMessage());
        }
    }

    // ==================== Distributed lock ====================

    private boolean tryLock() {
        String sql = "SELECT GET_LOCK(?, 0) AS got_lock";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DIGEST_LOCK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("got_lock") == 1;
            }
        } catch (SQLException e) {
            log.warn("tryLock() failed: {}", e.getMessage());
            return false;
        }
    }

    private void releaseLock() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, DIGEST_LOCK);
            ps.executeQuery();
        } catch (SQLException e) {
            log.debug("releaseLock() failed: {}", e.getMessage());
        }
    }
}
