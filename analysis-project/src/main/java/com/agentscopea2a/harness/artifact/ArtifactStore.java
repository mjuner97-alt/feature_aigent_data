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
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Per-tenant artifact storage. Delegates physical IO to an {@link ArtifactIo} backend so the same
 * tenant/cleanup/path-mounting logic works against the local FS or a remote SSH host.
 *
 * <p>Tools like {@code QualityTools} can return tabular data 24+ rows wide. Inlining that into
 * {@code ToolResultBlock.text(...)} forces the LLM to copy it verbatim into the next
 * {@code agent_spawn(task=...)} prompt — burning tokens and risking transcription errors. Instead,
 * the tool writes the data via this store, and returns a short handoff message containing the
 * {@link ArtifactRef#agentPath()} that downstream subagents can read directly.
 *
 * <p><b>Tenant isolation.</b> Every artifact lives under
 * {@code <root>/<userBucket>/<taskBucket>/<tool>-<uuid>.csv}.
 *
 * <p><b>GC.</b> {@link #cleanupTask} wipes the task bucket on request completion. Set
 * {@code keepArtifacts=true} to disable cleanup for debugging.
 */
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final ArtifactIo io;
    private final Path artifactsRoot;
    private final String mountPrefix;
    private final boolean keepArtifacts;

    /**
     * Legacy ctor — wires a {@link LocalArtifactIo} internally so callers and tests that don't
     * care about the backend abstraction continue to work unchanged.
     */
    public ArtifactStore(Path artifactsRoot, String mountPrefix, boolean keepArtifacts) {
        this(artifactsRoot, new LocalArtifactIo(artifactsRoot), mountPrefix, keepArtifacts);
    }

    /**
     * Explicit-backend ctor. Use this when wiring an {@link SshArtifactIo} for remote-Docker
     * deployments — pass the same {@code artifactsRoot} you'd use locally so {@code agentPath()}
     * keeps the same shape; the io delegate decides where the bytes actually land.
     */
    public ArtifactStore(
            Path artifactsRoot, ArtifactIo io, String mountPrefix, boolean keepArtifacts) {
        this.artifactsRoot = artifactsRoot;
        this.io = io;
        this.mountPrefix = stripTrailingSlash(mountPrefix);
        this.keepArtifacts = keepArtifacts;
    }

    public ArtifactRef save(
            ArtifactContext ctx,
            String toolName,
            String csv,
            List<String> columns,
            int rows,
            String previewMarkdown) {
        return save(ctx, toolName, csv, columns, rows, previewMarkdown, null);
    }

    public ArtifactRef save(
            ArtifactContext ctx,
            String toolName,
            String csv,
            List<String> columns,
            int rows,
            String previewMarkdown,
            List<com.agentscopea2a.harness.artifact.TabularExtractor.ColumnSchema> schema) {
        String id = toolName + "-" + UUID.randomUUID();
        String filename = id + ".csv";
        try {
            io.writeAtomic(ctx.userBucket(), ctx.taskBucket(), filename, csv);

            String agentPath =
                    mountPrefix
                            + "/"
                            + ctx.userBucket()
                            + "/"
                            + ctx.taskBucket()
                            + "/"
                            + filename;
            String backendPath = io.describePath(ctx.userBucket(), ctx.taskBucket(), filename);
            log.debug("Saved artifact backend={} agentPath={} rows={}", backendPath, agentPath, rows);
            return new ArtifactRef(
                    id, agentPath, backendPath, columns, rows, previewMarkdown, schema);
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
                    previewMarkdown,
                    schema);
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
        io.deleteBucket(ctx.userBucket(), ctx.taskBucket());
    }

    public Path artifactsRoot() {
        return artifactsRoot;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
