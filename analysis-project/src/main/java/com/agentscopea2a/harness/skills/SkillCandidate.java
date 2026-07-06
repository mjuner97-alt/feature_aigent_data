/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import java.time.LocalDateTime;

/**
 * Read-only snapshot of one row in {@code skill_candidate}.
 *
 * <p>PR2 — populated by {@link SkillSynthesisHook} every time a user question fingerprints to a
 * recognised {@code tenant|intent|dimensionKey}. When {@code hit_count} crosses
 * {@code harness.skills.auto-synth.threshold}, the hook distills a SKILL.md and marks this row
 * {@link #STATUS_SYNTHESIZED} so it stops triggering again.
 */
public record SkillCandidate(
        String fingerprint,
        String userId,
        int hitCount,
        String lastQuery,
        String lastTraceId,
        String metricTag,
        String status,
        String synthSkill,
        LocalDateTime updatedAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SYNTHESIZED = "synthesized";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_BLACKLIST = "blacklist";
}
