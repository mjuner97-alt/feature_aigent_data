/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.harness.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Per-tenant artifact storage backed by the local host filesystem.
 *
 * <p>Tools like {@code QualityTools} can return tabular data 24+ rows wide. Inlining that into
 * {@code ToolResultBlock.text(...)} forces the LLM to copy it verbatim into the next
 * {@code agent_spawn(task=...)} prompt — burning tokens and risking transcription errors. Instead,
 * the tool writes the data to a CSV here, and returns a short handoff message containing the
 * {@link ArtifactRef#agentPath()} that downstream subagents can read directly.
 *
 * <p><b>Tenant isolation.</b> Every artifact lives under
 * {@code <root>/<userBucket>/<taskBucket>/<tool>-<uuid>.csv}.
 *
 * <p><b>Atomic write.</b> Writes go to {@code .tmp + rename} so a reader can't observe a
 * half-written CSV.
 *
 * <p><b>GC.</b> {@link #cleanupTask} wipes the task bucket on request completion. Set
 * {@code keepArtifacts=true} to disable cleanup for debugging.
 */
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path artifactsRoot;
    private final String mountPrefix;
    private final boolean keepArtifacts;

    public ArtifactStore(Path artifactsRoot, String mountPrefix, boolean keepArtifacts) {
        this.artifactsRoot = artifactsRoot;
        this.mountPrefix = stripTrailingSlash(mountPrefix);
        this.keepArtifacts = keepArtifacts;
    }

    /**
     * Writes {@code csv} as a new artifact in the current tenant + task bucket.
     */
    public ArtifactRef save(
            ArtifactContext ctx,
            String toolName,
            String csv,
            List<String> columns,
            int rows,
            String previewMarkdown) {
        String id = toolName + "-" + UUID.randomUUID();
        String filename = id + ".csv";
        try {
            writeAtomic(ctx.userBucket(), ctx.taskBucket(), filename, csv);

            String agentPath =
                    mountPrefix
                            + "/"
                            + ctx.userBucket()
                            + "/"
                            + ctx.taskBucket()
                            + "/"
                            + filename;
            String backendPath = describePath(ctx.userBucket(), ctx.taskBucket(), filename);
            log.debug("Saved artifact backend={} agentPath={} rows={}", backendPath, agentPath, rows);
            return new ArtifactRef(id, agentPath, backendPath, columns, rows, previewMarkdown);
        } catch (IOException e) {
            log.warn(
                    "Artifact save failed for tool={} user={} task={}: {}",
                    toolName,
                    ctx.userBucket(),
                    ctx.taskBucket(),
                    e.getMessage());
            return new ArtifactRef(
                    "save-failed",
                    "(artifact-save-failed)",
                    "(none)",
                    columns,
                    rows,
                    previewMarkdown);
        }
    }

    public String agentPathPrefixFor(ArtifactContext ctx) {
        return mountPrefix + "/" + ctx.userBucket() + "/" + ctx.taskBucket() + "/";
    }

    public String agentPathRoot() {
        return mountPrefix + "/";
    }

    public void cleanupTask(ArtifactContext ctx) {
        if (keepArtifacts) {
            log.debug(
                    "cleanupTask skipped (keepArtifacts=true) for user={} task={}",
                    ctx.userBucket(),
                    ctx.taskBucket());
            return;
        }
        deleteBucket(ctx.userBucket(), ctx.taskBucket());
    }

    public Path artifactsRoot() {
        return artifactsRoot;
    }

    private void writeAtomic(String userBucket, String taskBucket, String filename, String content)
            throws IOException {
        Path dir = artifactsRoot.resolve(userBucket).resolve(taskBucket);
        Files.createDirectories(dir);

        Path target = dir.resolve(filename);
        Path tmp = dir.resolve(filename + ".tmp");

        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteBucket(String userBucket, String taskBucket) {
        Path dir = artifactsRoot.resolve(userBucket).resolve(taskBucket);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.debug("delete failed (ignored): {} - {}", p, e.getMessage());
                                }
                            });
            log.debug("Cleaned artifact bucket {}", dir);
        } catch (IOException e) {
            log.warn("Failed to clean artifact bucket {}: {}", dir, e.getMessage());
        }
    }

    private String describePath(String userBucket, String taskBucket, String filename) {
        return artifactsRoot
                .resolve(userBucket)
                .resolve(taskBucket)
                .resolve(filename)
                .toAbsolutePath()
                .toString();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
