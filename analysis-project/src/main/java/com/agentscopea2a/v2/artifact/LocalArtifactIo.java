/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.v2.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ArtifactIo} implementation backed by the local host filesystem.
 *
 * <p>Original behaviour previously inlined into {@link ArtifactStore} — extracted so
 * {@link SshArtifactIo} can coexist without changing the public surface.
 */
public final class LocalArtifactIo implements ArtifactIo {

    private static final Logger log = LoggerFactory.getLogger(LocalArtifactIo.class);

    private final Path artifactsRoot;

    public LocalArtifactIo(Path artifactsRoot) {
        this.artifactsRoot = artifactsRoot;
    }

    @Override
    public void writeAtomic(String userBucket, String taskBucket, String filename, String content)
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

    @Override
    public void deleteBucket(String userBucket, String taskBucket) {
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
                                    log.debug(
                                            "delete failed (ignored): {} - {}", p, e.getMessage());
                                }
                            });
            log.debug("Cleaned artifact bucket {}", dir);
        } catch (IOException e) {
            log.warn("Failed to clean artifact bucket {}: {}", dir, e.getMessage());
        }
    }

    @Override
    public String describePath(String userBucket, String taskBucket, String filename) {
        return artifactsRoot
                .resolve(userBucket)
                .resolve(taskBucket)
                .resolve(filename)
                .toAbsolutePath()
                .toString();
    }

    Path artifactsRoot() {
        return artifactsRoot;
    }
}
