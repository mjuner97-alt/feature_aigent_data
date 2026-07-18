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
package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * Records per-call activity into MySQL {@code agent_memory_ledger}, restoring three v1 scenarios
 * that broke when {@code MemoryFileWatcher} was not migrated:
 *
 * <ul>
 *   <li>{@code /debug/memory/ledger/{userId}} - per-user daily activity timeline via {@code tailLedger}</li>
 *   <li>{@code MemoryDigestionService.findActiveUsers()} - primary path queries ledger table</li>
 *   <li>Cross-replica user activity sync - shared MySQL is the source of truth</li>
 * </ul>
 *
 * <p><b>Design note</b>: v1's {@code MemoryFileWatcher} mirrored the daily ledger file
 * ({@code memory/<userId>/YYYY-MM-DD.jsonl}) to MySQL. v2's JAR writes a flat
 * {@code memory/YYYY-MM-DD.md} shared across users, and the sandbox filesystem is released
 * (via {@code Flux.using} eager cleanup) before middleware {@code concatWith} tails run -- so
 * reading the container file post-call is unreliable. Instead, this middleware captures the
 * agent's response from the event stream and records one ledger row per call attributed to
 * {@code rc.getUserId()}. The {@code line} column stores a timestamped activity summary with a
 * response snippet, giving {@code /debug/memory} a useful per-user timeline without depending
 * on sandbox lifecycle.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2MemoryConfig} when
 * {@code harness.a2a.memory.mysql-mirror.enabled=true}.
 */
public class MemoryLedgerMirrorMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MemoryLedgerMirrorMiddleware.class);

    private static final int RESPONSE_SNIPPET_CHARS = 300;

    private final MysqlMemoryStore store;
    private final Path localMemoryRoot;

    public MemoryLedgerMirrorMiddleware(MysqlMemoryStore store, Path localMemoryRoot) {
        this.store = store;
        this.localMemoryRoot = localMemoryRoot;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        StringBuilder responseCapture = new StringBuilder();
        return next.apply(input)
                .doOnNext(event -> captureResponseText(event, responseCapture))
                .doOnCancel(() -> log.warn(
                        "LedgerMirror upstream CANCELLED (no activity recorded): userId={}",
                        ctx != null ? ctx.getUserId() : null))
                .concatWith(
                        Mono.defer(() -> recordActivity(ctx, responseCapture.toString()))
                                .onErrorResume(
                                        e -> {
                                            log.warn("Ledger activity record failed: {}", e.getMessage());
                                            return Mono.empty();
                                        })
                                .then(Mono.<AgentEvent>empty()))
                .doFinally(signal -> {
                    // Spring's 10-min async timeout can cancel the upstream Flux before
                    // concatWith fires, losing the daily file write. doFinally runs on
                    // both complete and cancel signals, so the local file is always written
                    // even if the chat is cut off mid-stream.
                    String userId = ctx != null ? ctx.getUserId() : null;
                    if (userId == null || userId.isBlank()) {
                        return;
                    }
                    String today = java.time.LocalDate.now()
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
                    StringBuilder line = new StringBuilder();
                    line.append("## Activity - ").append(java.time.Instant.now().toString());
                    String snippet = responseCapture.toString();
                    if (!snippet.isBlank()) {
                        line.append("\nResponse: ").append(snippet);
                    }
                    writeLocalDailyFile(userId, today, line.toString());
                });
    }

    private void captureResponseText(AgentEvent event, StringBuilder out) {
        try {
            if (event == null) return;
            String text = null;
            if (event instanceof TextBlockDeltaEvent delta) {
                text = delta.getDelta();
            } else if (event instanceof AgentResultEvent result) {
                Msg msg = result.getResult();
                if (msg != null) {
                    text = msg.getTextContent();
                }
            }
            if (text == null || text.isBlank()) return;
            if (out.length() < RESPONSE_SNIPPET_CHARS) {
                int remaining = RESPONSE_SNIPPET_CHARS - out.length();
                out.append(text, 0, Math.min(text.length(), remaining));
            }
        } catch (Exception e) {
            log.debug("captureResponseText failed: {}", e.getMessage());
        }
    }

    private Mono<Void> recordActivity(RuntimeContext ctx, String responseSnippet) {
        String userId = ctx != null ? ctx.getUserId() : null;
        if (userId == null || userId.isBlank()) {
            return Mono.empty();
        }
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder line = new StringBuilder();
        line.append("## Activity - ").append(Instant.now().toString());
        if (!responseSnippet.isBlank()) {
            line.append("\nResponse: ").append(responseSnippet);
        }
        try {
            store.appendLedgerLine(userId, today, "v2-activity", line.toString());
            log.debug("Recorded ledger activity for user={} date={}", userId, today);
        } catch (Exception e) {
            log.warn("appendLedgerLine failed for user={} date={}: {}",
                    userId, today, e.getMessage());
        }
        writeLocalDailyFile(userId, today, line.toString());
        return Mono.empty();
    }

    /**
     * Writes the activity line to {@code <workspace>/memory/<userId>/<date>.md} on local disk.
     *
     * <p>The JAR's {@code MemoryFlushManager} attempts to write the same daily file to the sandbox
     * container via {@code SandboxBackedFilesystem.uploadFiles}, but on Windows the inline base64
     * payload trips the 8KB CreateProcess limit (error=206). This local write bypasses the sandbox
     * entirely so the daily file is visible on the host filesystem for {@code /debug/memory}
     * inspection and downstream digestion.
     */
    private void writeLocalDailyFile(String userId, String dateKey, String line) {
        if (localMemoryRoot == null) {
            return;
        }
        try {
            Path userDir = localMemoryRoot.resolve(userId);
            Files.createDirectories(userDir);
            Path dailyFile = userDir.resolve(dateKey + ".md");
            Files.writeString(
                    dailyFile,
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            log.debug("Wrote local daily memory file: {}", dailyFile);
        } catch (IOException e) {
            log.warn("writeLocalDailyFile failed for user={} date={}: {}",
                    userId, dateKey, e.getMessage());
        } catch (Exception e) {
            log.warn("writeLocalDailyFile unexpected failure for user={} date={}: {}",
                    userId, dateKey, e.getMessage());
        }
    }
}
