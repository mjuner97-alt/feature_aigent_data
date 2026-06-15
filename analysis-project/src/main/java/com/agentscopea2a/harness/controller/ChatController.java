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
package com.agentscopea2a.harness.controller;

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.dimension.DimensionStateManager;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.harness.service.SupervisorService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct REST endpoint mirroring the A2A runner's supervisor wiring.
 *
 * <p>Same as the old {@code /chatA2A} but backed by the harness-native {@link SupervisorService}.
 * Accepts an optional {@code session_id} for conversation isolation.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SupervisorService supervisorService;
    private final ResponseCacheService cacheService;
    private final DimensionStateManager cacheDimManager;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final ArtifactStore artifactStore;

    public ChatController(
            SupervisorService supervisorService,
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            ArtifactStore artifactStore) {
        this.supervisorService = supervisorService;
        this.cacheService = cacheService;
        this.cacheDimManager = cacheDimManager;
        this.meterRegistry = meterRegistry;
        this.artifactStore = artifactStore;
    }

    @PostMapping("/chatA2A")
    public ResponseEntity<?> chat(@RequestBody ChatRequest req) {
        if (req.message == null || req.message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Field 'message' is required"));
        }

        RuntimeContext ctx = null;
        if (req.sessionId != null && !req.sessionId.isBlank()) {
            ctx =
                    RuntimeContext.builder()
                            .sessionId(req.sessionId)
                            .sessionKey(SimpleSessionKey.of(req.sessionId))
                            .build();
        }

        ResponseCacheHook cacheHook =
                supervisorService.newCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);

        HarnessAgent agent = supervisorService.build(cacheHook, ctx);

        try {
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(req.message).build())
                            .build();

            String reply;
            try {
                Msg response = agent.call(userMsg, ctx).block();
                reply = extractText(response);
            } catch (ResponseCacheHook.CacheHitException e) {
                log.info("Cache HIT for /chatA2A");
                reply = e.getCachedResponse();
            }

            return ResponseEntity.ok(new ChatResponse(reply));
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof ResponseCacheHook.CacheHitException cacheHit) {
                log.info("Cache HIT for /chatA2A (wrapped)");
                return ResponseEntity.ok(new ChatResponse(cacheHit.getCachedResponse()));
            }
            log.error("Agent error", e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Agent error: " + e.getMessage()));
        } finally {
            // Mirror the HarnessA2aRunner GC: every request — successful, errored, or
            // cache-hit — must clean its per-task artifact bucket so concurrent users don't
            // accumulate stale CSVs.
            try {
                artifactStore.cleanupTask(ArtifactContext.from(ctx));
            } catch (Exception ex) {
                log.warn("Artifact cleanup failed for /chatA2A: {}", ex.getMessage());
            }
        }
    }

    private static String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    record ChatRequest(
            @JsonProperty("message") String message,
            @JsonProperty("session_id") String sessionId) {}

    record ChatResponse(@JsonProperty("reply") String reply) {}

    record ErrorResponse(@JsonProperty("error") String error) {}
}
