/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.artifact;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Backstop GC for {@code workspace/artifacts/} - wipes per-task subdirectories that have not been
 * touched in {@link #maxAgeHours} hours.
 *
 * <p><b>Why it exists.</b> The primary GC path is in {@code V2ChatStreamServiceImpl}
 * {@code doFinally} - every completed request cleans its own bucket. But three corner cases
 * escape that:
 *
 * <ul>
 *   <li>JVM crash mid-task - {@code doFinally} never fires;
 *   <li>client connection drops before reactor terminal signal - same;
 *   <li>requests that bypass our runner / controller entirely (future endpoints).
 * </ul>
 *
 * <p>This sweeper makes the artifact directory <b>self-healing</b>: no matter what crashes, the
 * disk converges back to "nothing older than {@link #maxAgeHours}h". Tunable via
 * {@code harness.a2a.artifacts.sweeper.*} in application.yml.
 *
 * <p><b>Cron cadence.</b> Defaults to hourly (jittered to {@code :17} to avoid the standard 0/30
 * cron stampede). Fast enough to bound disk usage; slow enough to never matter at the per-call
 * scale.
 *
 * <p><b>Bean condition.</b> Disabled when {@code harness.a2a.artifacts.keep=true} (debug mode) so
 * a developer poking at artifacts after a request finished doesn't lose them mid-inspection.
 *
 * <p><b>Wiring.</b> Created by {@link com.agentscopea2a.v2.config.V2InfraConfig} - not
 * component-scanned. The {@code @Component}/{@code @Value}/{@code @Autowired} annotations have
 * been removed in favor of explicit construction with constructor parameters.
 */
public class ArtifactSweeper {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSweeper.class);

    private final long maxAgeHours;
    private final boolean enabled;
    private final boolean keepArtifacts;
    private final Path artifactsRoot;

    public ArtifactSweeper(Path workspace, long maxAgeHours, boolean enabled, boolean keepArtifacts) {
        this.artifactsRoot = workspace.resolve("artifacts");
        this.maxAgeHours = maxAgeHours;
        this.enabled = enabled;
        this.keepArtifacts = keepArtifacts;
    }

    @PostConstruct
    void announce() {
        if (!enabled || keepArtifacts) {
            log.info(
                    "ArtifactSweeper disabled (enabled={}, keepArtifacts={})",
                    enabled,
                    keepArtifacts);
            return;
        }
        log.info(
                "ArtifactSweeper enabled - scanning {} every hour, evicting >{}h-old buckets",
                artifactsRoot,
                maxAgeHours);
    }

    /**
     * Hourly sweep, at minute 17 to avoid the :00 / :30 cron stampede when multiple replicas run.
     * The startup delay (initialDelay=300_000 = 5 min) lets the app warm up before doing any
     * disk walks.
     */
    @Scheduled(cron = "0 17 * * * *")
    public void sweep() {
        if (!enabled || keepArtifacts) {
            return;
        }
        if (!Files.isDirectory(artifactsRoot)) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(maxAgeHours));
        int removed = 0;
        try (Stream<Path> userBuckets = Files.list(artifactsRoot)) {
            for (Path userDir : (Iterable<Path>) userBuckets::iterator) {
                if (!Files.isDirectory(userDir)) continue;
                try (Stream<Path> taskBuckets = Files.list(userDir)) {
                    for (Path taskDir : (Iterable<Path>) taskBuckets::iterator) {
                        if (!Files.isDirectory(taskDir)) continue;
                        if (isStale(taskDir, cutoff)) {
                            wipeBucket(taskDir);
                            removed++;
                        }
                    }
                }
                // Best-effort cleanup of now-empty user directory; leave it if anything remains.
                deleteIfEmpty(userDir);
            }
        } catch (IOException e) {
            log.warn("ArtifactSweeper IO error walking {}: {}", artifactsRoot, e.getMessage());
        }
        if (removed > 0) {
            log.info(
                    "ArtifactSweeper wiped {} stale task bucket(s) under {}",
                    removed,
                    artifactsRoot);
        }
    }

    /**
     * A task bucket is stale when its newest file's mtime is older than the cutoff. We don't
     * use the directory's own mtime because some filesystems don't bump it on child writes.
     */
    private boolean isStale(Path taskDir, Instant cutoff) throws IOException {
        try (Stream<Path> walk = Files.walk(taskDir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                FileTime mtime = Files.getLastModifiedTime(p);
                if (mtime.toInstant().isAfter(cutoff)) {
                    return false; // still active, leave it
                }
            }
        }
        return true; // every file older than cutoff (or no files at all)
    }

    private void wipeBucket(Path taskDir) {
        try (Stream<Path> walk = Files.walk(taskDir)) {
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
        } catch (IOException e) {
            log.warn("Failed to wipe stale bucket {}: {}", taskDir, e.getMessage());
        }
    }

    private void deleteIfEmpty(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
            // Don't care - empty-dir cleanup is purely cosmetic
        }
    }
}
