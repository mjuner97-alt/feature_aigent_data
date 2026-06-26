/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import com.agentscopea2a.agent.tools.routers.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * OpenAI-compatible {@code POST /embeddings} client. Drops in unchanged against ARK / Volces /
 * any provider that mirrors the OpenAI embeddings API contract:
 *
 * <pre>
 * POST {endpoint}
 * Authorization: Bearer {api-key}
 * { "model": "...", "input": "..." }
 * → { "data": [ { "embedding": [...float...] } ] }
 * </pre>
 *
 * <p><b>Conditional</b>: only materialises when {@code harness.embedding.enabled=true}. Default
 * off — until embeddings are wired into a working endpoint, {@link SkillRetrievalHook} sees a
 * missing bean via {@code ObjectProvider} and silently falls back to L1-only routing.
 *
 * <p>Failure modes (all → return {@code null}, never throw):
 * <ul>
 *   <li>HTTP 4xx/5xx or transport failure
 *   <li>JSON missing {@code data[0].embedding}
 *   <li>Returned vector length ≠ configured {@link #dimension()}
 * </ul>
 *
 * <p>That keeps embedding hiccups from blocking user replies — the cost of a fall-through is
 * one extra full-skill-block injection by {@code WorkspaceContextHook}, not a 500.
 */
@Component
@ConditionalOnProperty(name = "harness.embedding.enabled", havingValue = "true")
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatEmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public OpenAiCompatEmbeddingClient(
            @Value("${harness.embedding.endpoint}") String endpoint,
            @Value("${harness.embedding.api-key:}") String apiKey,
            @Value("${harness.embedding.model:text-embedding-3-small}") String model,
            @Value("${harness.embedding.dim:1536}") int dimension) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String body = MAPPER.writeValueAsString(Map.of("model", model, "input", text));
            Map<String, String> headers =
                    apiKey == null || apiKey.isBlank()
                            ? Map.of()
                            : Map.of("Authorization", "Bearer " + apiKey);
            String resp = HttpClient.postJson(endpoint, body, headers);
            if (resp == null || resp.isBlank()) return null;
            JsonNode root = MAPPER.readTree(resp);
            JsonNode vec = root.path("data").path(0).path("embedding");
            if (!vec.isArray() || vec.size() == 0) {
                log.warn("Embedding response missing data[0].embedding: {}", truncate(resp));
                return null;
            }
            if (vec.size() != dimension) {
                // A misconfigured dim would corrupt every cosine forever — refuse rather than
                // silently mix two vector spaces in one column.
                log.warn(
                        "Embedding length {} != configured dim {} (model={}); ignoring",
                        vec.size(),
                        dimension,
                        model);
                return null;
            }
            float[] out = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                out[i] = (float) vec.get(i).asDouble();
            }
            return out;
        } catch (Exception e) {
            log.warn("Embedding call failed (model={}): {}", model, e.getMessage());
            return null;
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
