/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import java.time.LocalDateTime;

/**
 * Read-only snapshot of one row in {@code skill_index}.
 *
 * <p>PR1 only persists baseline observability metadata — {@code embedding}, {@code success_count}
 * and {@code failure_count} columns exist in the DDL so PR3/PR4 can write them later without
 * another schema migration, but this record does not expose them.
 */
public record SkillEntry(
        String name,
        String description,
        int version,
        int usageCount,
        LocalDateTime lastUsed,
        String status,
        LocalDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_BLACKLIST = "blacklist";
    public static final String STATUS_PENDING = "pending";
}
