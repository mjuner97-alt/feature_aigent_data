/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * DB -> file hydrator for {@code <workspace>/memory/<userId>/MEMORY.md}.
 *
 * <p>The harness's built-in {@code WorkspaceContextHook} reads MEMORY.md from disk to inject
 * into the system prompt. We don't want to fork that hook just to swap in a JDBC read, so
 * instead this component pulls the latest body from {@link MysqlMemoryStore} into the file
 * right before {@code SupervisorService.build()} returns. From the harness's perspective the
 * file is the source of truth - but from the operator's perspective MySQL is, and the file
 * is a per-replica cache the watcher keeps fresh.
 *
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2MemoryConfig}
 * when {@code harness.a2a.memory.mysql-mirror.enabled=true} - not component-scanned.
 * The {@code @Component}/{@code @ConditionalOnProperty}/{@code @Autowired} annotations
 * have been removed in favor of explicit construction in the config class.
 */
public class MemoryHydrator {

    private static final Logger log = LoggerFactory.getLogger(MemoryHydrator.class);

    private final Path workspaceMemoryRoot;
    private final MysqlMemoryStore store;

    public MemoryHydrator(Path workspaceMemoryRoot, MysqlMemoryStore store) {
        this.workspaceMemoryRoot = workspaceMemoryRoot;
        this.store = store;
    }

    /**
     * If the DB has a fresher MEMORY.md than the file, write the DB version into the file.
     * Compares MEDIUMTEXT contents; we don't trust mtime here because the file is often newer
     * (host JVM hook flushed) and the DB is the mirror - overwriting the file would lose data
     * the watcher hasn't yet ingested. Net effect: this is a one-way recovery for the case
     * where the file is missing/stale (fresh boot, replica that never ran the user before).
     */
    public void hydrate(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Optional<String> dbBody = store.read(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md");
        if (dbBody.isEmpty()) {
            return;
        }
        Path userDir = workspaceMemoryRoot.resolve(userId);
        Path file = userDir.resolve("MEMORY.md");
        try {
            String fileBody =
                    Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (fileBody.equals(dbBody.get())) {
                return;
            }
            // Only push DB -> file when the file is empty or absent; otherwise trust the file
            // (host hook just wrote it) and let the watcher mirror upward on the next tick.
            if (fileBody.isBlank()) {
                Files.createDirectories(userDir);
                Files.writeString(file, dbBody.get(), StandardCharsets.UTF_8);
                log.debug("Hydrated MEMORY.md from DB for user={} ({} bytes)",
                        userId, dbBody.get().length());
            }
        } catch (IOException e) {
            log.warn("hydrate({}) failed: {}", userId, e.getMessage());
        }
    }
}
