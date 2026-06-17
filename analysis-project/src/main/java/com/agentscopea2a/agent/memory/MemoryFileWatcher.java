/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.agent.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Polling watcher for {@code <workspace>/memory/<userId>/...} files.
 *
 * <p>The host JVM (and, when sandbox is enabled, the container) writes memory files into the
 * workspace memory tree via the harness {@code MemoryFlushHook}. We mirror those changes into
 * MySQL ({@link MysqlMemoryStore}) so the database becomes the cross-replica source of truth
 * and {@code /debug/memory} can render from DB instead of scanning files.
 *
 * <p>The mirror is asynchronous by construction: agent code only ever writes the local file;
 * a daemon thread picks the change up on the next poll and pushes it to MySQL. This means
 * agent latency is unaffected by DB hiccups, and a transient DB outage just queues writes in
 * memory (the file is the buffer) until the next successful tick.
 *
 * <p>Why polling rather than {@code java.nio.file.WatchService}: WatchService's behaviour
 * differs across Windows / macOS / Linux and works poorly when the directory is a Docker
 * bind-mount target (events from inside the container don't always fire on the host). A 5s
 * mtime poll over a tiny tree is cheap and uniform.
 *
 * <p>Tenant safety: the {@code userId} column written to {@code agent_memory} is derived from
 * the directory name on disk, not a RuntimeContext. If a hallucinating LLM writes to {@code
 * memory/bob/MEMORY.md} during alice's task, the audit trail correctly attributes it to bob —
 * and a warn is logged so it shows up in operations.
 *
 * <p>Disabled when {@code harness.a2a.memory.mysql-mirror.enabled=false}; the bean simply
 * doesn't get created and writes stay file-only (legacy behaviour).
 */
@Component
@ConditionalOnProperty(
        prefix = "harness.a2a.memory.mysql-mirror",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class MemoryFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileWatcher.class);

    private static final Pattern LEDGER_NAME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.jsonl$");
    private static final Pattern USER_DIR = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    private final Path workspaceMemoryRoot;
    private final MysqlMemoryStore store;
    private final long pollSeconds;

    /** path → last-known mtime (millis). Files we've already mirrored. */
    private final Map<Path, Long> seenMtimes = new ConcurrentHashMap<>();
    /** ledger path → last byte offset we've ingested into the DB. */
    private final Map<Path, Long> seenLedgerOffsets = new ConcurrentHashMap<>();

    private ScheduledExecutorService poller;

    public MemoryFileWatcher(
            Path workspace,
            MysqlMemoryStore store,
            @Value("${harness.a2a.memory.mysql-mirror.poll-seconds:5}") long pollSeconds) {
        this.workspaceMemoryRoot = workspace.resolve("memory");
        this.store = store;
        this.pollSeconds = Math.max(1, pollSeconds);
    }

    @PostConstruct
    void start() {
        try {
            Files.createDirectories(workspaceMemoryRoot);
        } catch (IOException e) {
            log.warn("Could not create memory root {}: {}", workspaceMemoryRoot, e.getMessage());
        }
        // One-time reconciliation: ingest whatever's already on disk into the DB so a fresh
        // boot picks up any files written while the JVM was offline.
        try {
            scanOnce();
        } catch (Exception e) {
            log.warn("Initial memory scan failed: {}", e.getMessage());
        }
        poller =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "memory-file-watcher");
                            t.setDaemon(true);
                            return t;
                        });
        poller.scheduleWithFixedDelay(this::tick, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        log.info(
                "MemoryFileWatcher polling {} every {}s", workspaceMemoryRoot, pollSeconds);
    }

    @PreDestroy
    void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    private void tick() {
        try {
            scanOnce();
        } catch (Exception e) {
            log.warn("Memory scan tick failed: {}", e.getMessage());
        }
    }

    private void scanOnce() throws IOException {
        if (!Files.isDirectory(workspaceMemoryRoot)) {
            return;
        }
        try (Stream<Path> userDirs = Files.list(workspaceMemoryRoot)) {
            userDirs
                    .filter(Files::isDirectory)
                    .forEach(
                            userDir -> {
                                String userId = userDir.getFileName().toString();
                                if (!USER_DIR.matcher(userId).matches()) {
                                    log.warn(
                                            "Skipping suspicious memory subdir: {} (not a clean userId)",
                                            userDir);
                                    return;
                                }
                                try {
                                    scanUser(userId, userDir);
                                } catch (IOException e) {
                                    log.warn(
                                            "scan {} failed: {}",
                                            userDir,
                                            e.getMessage());
                                }
                            });
        }
    }

    private void scanUser(String userId, Path userDir) throws IOException {
        try (Stream<Path> files = Files.list(userDir)) {
            files.forEach(
                    f -> {
                        if (!Files.isRegularFile(f)) return;
                        String name = f.getFileName().toString();
                        try {
                            if (name.equals("MEMORY.md")) {
                                handleMemoryMd(userId, f);
                            } else if (LEDGER_NAME.matcher(name).matches()) {
                                handleLedger(userId, f);
                            }
                            // Other files (skills/, tmp) are ignored — only MEMORY.md and daily
                            // jsonl are part of the memory contract.
                        } catch (Exception e) {
                            log.warn("Failed to mirror {} → DB: {}", f, e.getMessage());
                        }
                    });
        }
    }

    private void handleMemoryMd(String userId, Path file) throws IOException {
        long mtime = readMtime(file);
        Long seen = seenMtimes.get(file);
        if (seen != null && seen == mtime) {
            return; // unchanged
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        store.upsert(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md", body);
        seenMtimes.put(file, mtime);
        log.debug("Mirrored MEMORY.md for user={} ({} bytes)", userId, body.length());
    }

    private void handleLedger(String userId, Path file) throws IOException {
        long size = Files.size(file);
        long seen = seenLedgerOffsets.getOrDefault(file, 0L);
        if (size <= seen) {
            return; // no new bytes (or file was rotated; we'd see size shrink)
        }
        String date = file.getFileName().toString().replace(".jsonl", "");
        // Read tail; for bind-mounts this is the simplest correct path. The whole-file read is
        // cheap because daily ledgers cap at low MB before consolidation.
        byte[] all = Files.readAllBytes(file);
        if (seen >= all.length) {
            seenLedgerOffsets.put(file, (long) all.length);
            return;
        }
        String tail = new String(all, (int) seen, all.length - (int) seen, StandardCharsets.UTF_8);
        long inserted = 0;
        for (String line : tail.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            store.appendLedgerLine(userId, date, "host", trimmed);
            inserted++;
        }
        seenLedgerOffsets.put(file, (long) all.length);
        if (inserted > 0) {
            log.debug(
                    "Mirrored {} new ledger line(s) for user={} date={}",
                    inserted,
                    userId,
                    date);
        }
    }

    private static long readMtime(Path file) throws IOException {
        FileTime ft = Files.getLastModifiedTime(file);
        return ft.toMillis();
    }

    /** Test-only seam: snapshot what we've ingested. */
    Map<Path, Long> snapshotMtimes() {
        return new HashMap<>(seenMtimes);
    }
}
