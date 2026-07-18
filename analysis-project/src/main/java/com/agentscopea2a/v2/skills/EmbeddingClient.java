/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

/**
 * Tiny abstraction so embedding consumers can ask "embed this string" without knowing
 * which provider answers. Ships {@link OpenAiCompatEmbeddingClient} backed by the
 * OpenAI / Volces-compatible {@code POST /embeddings} endpoint, but a noop / mock impl can be
 * dropped in for tests or when no embedding provider is configured.
 *
 * <p>v2 relocation: moved from {@code harness.skills} to {@code v2.skills} to escape
 * the v1 compile-exclude shadow.
 *
 * <p>Implementations <b>must not throw</b>: returning {@code null} signals "skip vector
 * retrieval, fall back to L1 / full-injection". The retrieval hook never aborts a request just
 * because an embedding call failed.
 */
public interface EmbeddingClient {

    /** @return float[] of {@link #dimension()} length, or {@code null} when embedding failed. */
    float[] embed(String text);

    /** Output vector length. Used for upsert validation. */
    int dimension();
}
