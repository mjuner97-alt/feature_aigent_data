/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;

/**
 * Read-only snapshot of one row in {@code skill_candidate}.
 *
 * <p>Populated by skill synthesis hooks every time a user question fingerprints to a
 * recognised metric. When {@code hit_count} crosses the threshold, the hook distills a
 * SKILL.md and marks this row {@link #STATUS_SYNTHESIZED} so it stops triggering again.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2SkillConfig}.
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
