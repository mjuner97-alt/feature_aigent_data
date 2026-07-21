/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.memory;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FTS + vector search for episodic memory. Extracted from {@code MySqlEpisodicMemory}
 * (P2-1) so search internals (SQL, cosine math, embedding lookup) live separately
 * from the public {@link EpisodicMemory} API.
 *
 * <p>Two search modes:
 * <ul>
 *   <li>{@link #ftsSearch} - MySQL FULLTEXT index on the {@code content} column</li>
 *   <li>{@link #vectorSearch} - cosine similarity against stored embeddings (bge-large-zh)</li>
 * </ul>
 *
 * <p>Vector search requires an {@link EmbeddingClient} and {@code vectorSearchEnabled=true}
 * in {@link EpisodicMemoryConfig}. When either is missing, callers fall back to FTS.
 */
final class EpisodicSearcher {

    private static final Logger log = LoggerFactory.getLogger(EpisodicSearcher.class);
    private static final int SNIPPET_MAX_LEN = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<float[]> FLOAT_ARRAY = new TypeReference<>() {};

    private final ConnectionSupplier connectionSupplier;
    private final EpisodicMemoryConfig config;
    private final EmbeddingClient embeddingClient;

    EpisodicSearcher(ConnectionSupplier connectionSupplier,
                     EpisodicMemoryConfig config,
                     EmbeddingClient embeddingClient) {
        this.connectionSupplier = connectionSupplier;
        this.config = config;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Full-text search for past conversation snippets matching {@code query}.
     *
     * @param userId when non-null and non-blank, restricts results to rows whose
     *     {@code user_id} column matches (tenant isolation). When null/blank, no
     *     user_id filter is applied (global search — legacy behavior, used only
     *     by {@code EpisodicMemoryTools.episodic_search} which has no per-user
     *     context at the tool-call site).
     */
    List<EpisodicResult> ftsSearch(String userId, String query, int limit) {
        boolean filterUser = userId != null && !userId.isBlank();
        String sql =
                "SELECT session_id, content, "
                        + "MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) AS score, "
                        + "created_at FROM "
                        + this.config.getTableName()
                        + " WHERE MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) "
                        + (filterUser ? " AND user_id = ? " : "")
                        + "ORDER BY score DESC LIMIT ?";
        List<EpisodicResult> results = new ArrayList<>();
        try (Connection conn = connectionSupplier.get();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, query);
            stmt.setString(2, query);
            int nextIdx = 3;
            if (filterUser) {
                stmt.setString(nextIdx++, userId);
            }
            stmt.setInt(nextIdx, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(buildResult(rs));
                }
            }
        } catch (SQLException e) {
            log.error("FTS search failed (userId={}, queryLen={}): {}", userId, query.length(), e.getMessage());
            throw new RuntimeException("Failed to search episodic memory", e);
        }
        return results;
    }

    /**
     * Vector (cosine similarity) search for past conversation snippets matching {@code query}.
     *
     * @param userId same scoping contract as {@link #ftsSearch} — null/blank = global,
     *     non-blank = restrict to that user's rows.
     */
    List<EpisodicResult> vectorSearch(String userId, String query, int limit) {
        float[] queryVec = embeddingClient.embed(query);
        if (queryVec == null || queryVec.length == 0) return List.of();
        boolean filterUser = userId != null && !userId.isBlank();
        String sql =
                "SELECT session_id, content, embedding, created_at FROM "
                        + this.config.getTableName()
                        + " WHERE embedding IS NOT NULL "
                        + (filterUser ? " AND user_id = ? " : "")
                        + "ORDER BY id DESC LIMIT ?";
        List<EpisodicResult> candidates = new ArrayList<>();
        try (Connection conn = connectionSupplier.get();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            if (filterUser) {
                stmt.setString(idx++, userId);
            }
            stmt.setInt(idx, limit * 10); // sample a wider pool
            try (ResultSet rs = stmt.executeQuery()) {
                float qNorm = norm(queryVec);
                if (qNorm == 0f) return List.of();
                while (rs.next()) {
                    String json = rs.getString("embedding");
                    if (json == null || json.isBlank()) continue;
                    float[] vec;
                    try {
                        vec = MAPPER.readValue(json, FLOAT_ARRAY);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (vec.length != queryVec.length) continue;
                    float cos = cosine(queryVec, vec, qNorm);
                    if (cos >= config.getVectorMinCosine()) {
                        String sessionId = rs.getString("session_id");
                        String content = rs.getString("content");
                        Timestamp ts = rs.getTimestamp("created_at");
                        LocalDateTime timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                        String snippet = content != null && content.length() > SNIPPET_MAX_LEN
                                ? content.substring(0, SNIPPET_MAX_LEN) + "..."
                                : content;
                        candidates.add(new EpisodicResult(sessionId, snippet, cos, timestamp));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Vector search failed (userId={}), falling back to FTS: {}", userId, e.getMessage());
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble(EpisodicResult::relevance).reversed());
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    /** Best-effort embedding of content; returns null when embedding is disabled or fails. */
    String embedContent(String content) {
        if (!config.isVectorSearchEnabled() || embeddingClient == null) return null;
        if (content == null || content.isBlank()) return null;
        try {
            float[] vec = embeddingClient.embed(content);
            if (vec == null || vec.length == 0) return null;
            return MAPPER.writeValueAsString(vec);
        } catch (Exception ex) {
            log.debug("Embedding failed for content ({} chars): {}", content.length(), ex.getMessage());
            return null;
        }
    }

    private static float norm(float[] v) {
        double s = 0d;
        for (float x : v) s += x * x;
        return (float) Math.sqrt(s);
    }

    private static float cosine(float[] a, float[] b, float aNorm) {
        double dot = 0d;
        double bNorm = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            bNorm += b[i] * b[i];
        }
        double denom = aNorm * Math.sqrt(bNorm);
        return denom == 0d ? 0f : (float) (dot / denom);
    }

    private static EpisodicResult buildResult(ResultSet rs) throws SQLException {
        String sessionId = rs.getString("session_id");
        String content = rs.getString("content");
        double score = rs.getDouble("score");
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
        String snippet = content != null && content.length() > SNIPPET_MAX_LEN
                ? content.substring(0, SNIPPET_MAX_LEN) + "..."
                : content;
        return new EpisodicResult(sessionId, snippet, score, timestamp);
    }
}
