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
package com.agentscopea2a.harness.runner;

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.harness.hooks.ToolCallCollector;
import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import com.agentscopea2a.service.SupervisorService;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A protocol adapter: bridges the harness-native {@link HarnessAgent} into the
 * {@link AgentRunner} contract expected by the {@code agentscope-a2a-spring-boot-starter}.
 *
 * <p>Each A2A request creates a fresh {@link HarnessAgent} via {@link SupervisorService#build},
 * streams through {@code agent.stream(msgs, ctx)}, and removes the agent from the active cache
 * on completion. The {@code SessionPersistenceHook} inside the agent auto-loads prior state for
 * the given {@code taskId} and auto-saves after the call — no manual saveState / loadState.
 */
@Component
public class HarnessA2aRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(HarnessA2aRunner.class);

    private final SupervisorService supervisorService;
    private final ResponseCacheService cacheService;
    private final DimensionStateManager cacheDimManager;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final ArtifactStore artifactStore;

    private final Map<String, HarnessAgent> active = new ConcurrentHashMap<>();

    public HarnessA2aRunner(
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

    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        if (active.containsKey(options.getTaskId())) {
            throw new IllegalStateException(
                    "Agent already exists for taskId: " + options.getTaskId());
        }

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId(options.getTaskId())
                        .userId(options.getUserId())
                        .sessionKey(SimpleSessionKey.of(options.getTaskId()))
                        .build();

        // Extract user question for ToolCallCollector
        String userQuestion = extractUserQuestion(requestMessages);

        // Create and bind ToolCallCollector for this request
        ToolCallCollector collector = new ToolCallCollector(userQuestion);
        collector.bind();

        // ctx scopes the cache key by userId; meterRegistry records hit/miss/write counters.
        ResponseCacheHook cacheHook =
                supervisorService.newCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);

        HarnessAgent agent = supervisorService.build(cacheHook, ctx);
        active.put(options.getTaskId(), agent);

        return agent.stream(requestMessages, ctx)
                .onErrorResume(
                        ResponseCacheHook.CacheHitException.class,
                        e -> {
                            log.info("Cache HIT for taskId={}", options.getTaskId());
                            // Record cache HIT to episodic memory so it leaves a trace (P2-3).
                            // Without this, cache HITs are invisible to episodic memory and the
                            // long-term memory becomes increasingly biased toward cache MISS interactions.
                            recordCacheHitToEpisodic(options, e.getCachedResponse());
                            return Flux.just(buildCachedEvent(e.getCachedResponse()));
                        })
                .doFinally(
                        signal -> {
                            active.remove(options.getTaskId());
                            // Persist tool call context to episodic memory for future distillation
                            persistToolCallContext(options, collector);
                            // Unbind the ThreadLocal collector
                            collector.unbind();
                            // GC the per-task artifact bucket. Done on EVERY terminal signal
                            // (complete / error / cancel) so a crashed task still cleans up.
                            // The bucket is tiny — a few CSVs — but with N concurrent users
                            // this prevents unbounded growth without needing a cron sweeper.
                            try {
                                artifactStore.cleanupTask(ArtifactContext.from(ctx));
                            } catch (Exception ex) {
                                log.warn(
                                        "Artifact cleanup failed for taskId={}: {}",
                                        options.getTaskId(),
                                        ex.getMessage());
                            }
                        });
    }

    @Override
    public void stop(String taskId) {
        HarnessAgent a = active.remove(taskId);
        if (a != null) {
            a.interrupt();
        }
    }

    @Override
    public String getAgentName() {
        return "QualitySupervisor";
    }

    @Override
    public String getAgentDescription() {
        return "Harness-native quality data supervisor with multi-agent coordination";
    }

    private static Event buildCachedEvent(String cachedResponse) {
        Msg msg =
                Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(cachedResponse).build())
                        .build();
        return new Event(EventType.REASONING, msg, true);
    }

    /**
     * Extract the first USER message text from the request messages.
     * Used to seed the ToolCallCollector with the user's query.
     */
    private static String extractUserQuestion(List<Msg> requestMessages) {
        if (requestMessages == null) return "";
        for (Msg m : requestMessages) {
            if (m != null && m.getRole() == MsgRole.USER) {
                String text = m.getTextContent();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return "";
    }

    /**
     * Persist the ToolCallCollector's JSON to episodic memory for use by Path B
     * (online auto-synthesis). Best-effort: failures are logged and swallowed.
     */
    private void persistToolCallContext(AgentRequestOptions options, ToolCallCollector collector) {
        String json = collector.toJson();
        if (json == null || json.isBlank()) return;
        try {
            String taskId = options.getTaskId();
            String sessionId = (taskId != null ? taskId : "req_" + System.currentTimeMillis())
                    + ":" + System.currentTimeMillis();
            log.debug("Persisting tool call context to episodic memory ({} chars)", json.length());
            MySqlEpisodicMemory mem = supervisorService.getEpisodicMemory();
            if (mem != null) {
                mem.recordSessionWithToolContext(sessionId, List.of(), json)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                v -> {},
                                ex -> log.debug("Failed to persist tool call context: {}", ex.getMessage()));
            }
        } catch (Exception ex) {
            log.debug("persistToolCallContext failed: {}", ex.getMessage());
        }
    }

    /**
     * Records a cache-HIT interaction to episodic memory so the long-term memory isn't biased
     * toward cache MISS-only conversations. Best-effort: failures are logged and swallowed.
     */
    private void recordCacheHitToEpisodic(AgentRequestOptions options, String cachedResponse) {
        if (supervisorService.getEpisodicMemory() == null) return;
        try {
            // Use taskId + userId as a proxy for the user question. The full question text
            // isn't available on AgentRequestOptions in 1.1.0-RC2 (no getDescription()),
            // so we use the taskId which often encodes the question for traceability.
            String userQuestion = "Cache HIT for task: " + options.getTaskId();
            if (options.getUserId() != null && !options.getUserId().isBlank()) {
                userQuestion = userQuestion + " user:" + options.getUserId();
            }

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(userQuestion).build())
                    .build();
            Msg assistantMsg = Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder().text(cachedResponse).build())
                    .build();
            String sessionId = "cache-hit:" + options.getTaskId() + ":" + System.currentTimeMillis();
            supervisorService.getEpisodicMemory().recordSession(sessionId, List.of(userMsg, assistantMsg))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            v -> log.debug("Cache HIT recorded to episodic memory: {}", sessionId),
                            ex -> log.debug("Failed to record cache HIT to episodic memory: {}", ex.getMessage()));
        } catch (Exception ex) {
            log.debug("recordCacheHitToEpisodic failed: {}", ex.getMessage());
        }
    }
}
