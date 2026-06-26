/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

/**
 * Tiny abstraction so {@link SkillRetrievalHook} can ask "embed this string" without knowing
 * which provider answers. PR3 ships {@link OpenAiCompatEmbeddingClient} backed by the
 * OpenAI / Volces-compatible {@code POST /embeddings} endpoint, but a noop / mock impl can be
 * dropped in for tests or when no embedding provider is configured.
 *
 * <p>Implementations <b>must not throw</b>: returning {@code null} signals "skip vector
 * retrieval, fall back to L1 / full-injection". The retrieval hook never aborts a request just
 * because an embedding call failed.
 */
public interface EmbeddingClient {

    /** @return float[] of {@link #dimension()} length, or {@code null} when embedding failed. */
    float[] embed(String text);

    /** Output vector length. Used by {@link SkillVectorIndex} for upsert validation. */
    int dimension();
}
