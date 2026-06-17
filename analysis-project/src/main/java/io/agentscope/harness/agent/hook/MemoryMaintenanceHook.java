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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

// 本地影子覆盖 JAR 中的 MemoryMaintenanceHook(classpath 优先)。
// 唯一行为变化:LAST_RUN_AT 提为 JVM 静态变量,而非 per-instance。
// 原版每次构建 HarnessAgent 都创建新 hook 实例,lastRunAt 永远重置为 EPOCH,
// 30 分钟节流形同虚设 —— 每次 PostCall 都跑维护(LLM consolidate + glob/delete)。
// 改静态后节流为 JVM 全局,父 supervisor 与任何重启用 memory hook 的 subagent 共享同一时钟。
public class MemoryMaintenanceHook implements Hook {

    private static final RuntimeContext DEFAULT_FS_RUNTIME = RuntimeContext.empty();

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceHook.class);

    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    private static final AtomicReference<Instant> LAST_RUN_AT =
            new AtomicReference<>(Instant.EPOCH);

    private final WorkspaceManager workspaceManager;
    private final MemoryConsolidator consolidator;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;
    private final Duration minGap;

    public MemoryMaintenanceHook(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
    }

    public MemoryMaintenanceHook(
            WorkspaceManager workspaceManager, MemoryConsolidator consolidator) {
        this(workspaceManager, consolidator, 90, 180, DEFAULT_MIN_GAP);
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostCallEvent)) {
            return Mono.just(event);
        }
        Instant now = Instant.now();
        Instant last = LAST_RUN_AT.get();
        if (Duration.between(last, now).compareTo(minGap) < 0) {
            return Mono.just(event);
        }
        if (!LAST_RUN_AT.compareAndSet(last, now)) {
            return Mono.just(event);
        }
        // Fire-and-forget:维护任务包含 LLM consolidate + 文件清扫,调用方不等结果。
        // 阻塞 PostCall 在原版给每次响应加了 ~30s。LAST_RUN_AT CAS 已限频,异步无风险。
        Mono.fromRunnable(this::runMaintenance)
                .onErrorResume(
                        e -> {
                            log.warn("Memory maintenance failed: {}", e.getMessage());
                            return Mono.empty();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        return Mono.just(event);
    }

    private void runMaintenance() {
        log.debug("Running memory maintenance...");
        expireDailyFiles();
        consolidateMemory();
        pruneOldSessions();
        log.debug("Memory maintenance completed");
    }

    private void expireDailyFiles() {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(DEFAULT_FS_RUNTIME, "*.md", WorkspaceConstants.MEMORY_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String fileName = fileName(fi.path());
            if (fileName.startsWith(".")) {
                continue;
            }
            String baseName =
                    fileName.endsWith(".md")
                            ? fileName.substring(0, fileName.length() - 3)
                            : fileName;
            try {
                LocalDate fileDate = LocalDate.parse(baseName);
                if (fileDate.isBefore(cutoff)) {
                    String fromPath = WorkspaceConstants.MEMORY_DIR + "/" + fileName;
                    String toPath = WorkspaceConstants.MEMORY_DIR + "/archive/" + fileName;
                    fs.move(DEFAULT_FS_RUNTIME, fromPath, toPath);
                    log.debug("Archived expired daily file: {}", fileName);
                }
            } catch (Exception e) {
                // 非日期命名文件,跳过
            }
        }
    }

    private void consolidateMemory() {
        if (consolidator == null) {
            return;
        }
        try {
            consolidator.consolidate().block();
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }
    }

    private void pruneOldSessions() {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(DEFAULT_FS_RUNTIME, "*.log.jsonl", WorkspaceConstants.AGENTS_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(sessionRetentionDays));
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String modifiedAt = fi.modifiedAt();
            if (modifiedAt == null || modifiedAt.isEmpty()) {
                continue;
            }
            try {
                Instant modified = Instant.parse(modifiedAt);
                if (modified.isBefore(cutoff)) {
                    fs.delete(DEFAULT_FS_RUNTIME, fi.path());
                    log.debug("Pruned old session file: {}", fi.path());
                }
            } catch (Exception e) {
                log.warn("Failed to check/prune {}: {}", fi.path(), e.getMessage());
            }
        }
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
