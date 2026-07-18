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
package com.agentscopea2a.v2.config;

import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactIo;
import com.agentscopea2a.v2.artifact.ArtifactSweeper;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.artifact.LocalArtifactIo;
import com.agentscopea2a.v2.artifact.SshArtifactIo;
import com.agentscopea2a.v2.cache.ResponseCacheService;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.hooks.ArtifactHandoffHook;
import com.agentscopea2a.v2.hooks.PythonExecRetryHook;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.ResponseCacheMiddleware;
import com.agentscopea2a.v2.middleware.SessionMiddleware;
import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v2 infrastructure wiring: response cache, artifact store, and their middlewares/hooks.
 *
 * <p>Replaces v1's Spring configuration for:
 * <ul>
 *   <li>{@code ResponseCacheService} (MySQL-backed response cache)
 *   <li>{@code ArtifactStore} (per-tenant CSV artifact storage)
 *   <li>{@code ArtifactAccessMiddleware} (cross-tenant path guard — v2 middleware)
 *   <li>{@code ArtifactHandoffHook} (tabular tool result rewrite — v2 hook, pending migration
 *       to middleware once the framework exposes tool-result interception in the middleware chain)
 * </ul>
 */
@Configuration
public class V2InfraConfig {

    private static final Logger log = LoggerFactory.getLogger(V2InfraConfig.class);

    // ── Response Cache ─────────────────────────────────────────────────────

    /**
     * P0-4: Prometheus-backed {@link MeterRegistry} so metrics are scraped via
     * {@code /actuator/prometheus} instead of staying in-memory.
     *
     * <p>Replaces the previous {@code new SimpleMeterRegistry()} which was unreachable
     * from Prometheus. The same bean is injected into {@link ResponseCacheMiddleware}
     * and any other component that needs to record counters/timers.
     */
    @Bean
    public MeterRegistry meterRegistry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        log.info("PrometheusMeterRegistry: wired (scrape via /actuator/prometheus)");
        return registry;
    }

    /**
     * P0-4: Enables {@code @Timed} annotation on Spring bean methods. Without this bean,
     * {@code @Timed} is a no-op. AspectJ weaving picks up methods on Spring-managed beans
     * (V2ChatStreamServiceImpl / PythonExecTool / ToolRoutersIndex / FallbackModelDecorator).
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        log.info("TimedAspect: wired (enables @Timed on bean methods)");
        return new TimedAspect(meterRegistry);
    }

    @Bean
    public ResponseCacheService responseCacheService(DataSource dataSource) {
        return new ResponseCacheService(dataSource);
    }

    @Bean
    public ResponseCacheMiddleware responseCacheMiddleware(
            ResponseCacheService cacheService,
            DimensionStateManager dimManager,
            MeterRegistry meterRegistry,
            @Value("${harness.a2a.response-cache.enabled:false}") boolean cacheEnabled) {
        log.info("ResponseCacheMiddleware: enabled={}", cacheEnabled);
        return new ResponseCacheMiddleware(cacheService, dimManager, meterRegistry, cacheEnabled);
    }

    // ── Artifact Store ─────────────────────────────────────────────────────

    @Bean
    public ArtifactStore artifactStore(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.a2a.artifact.mount-prefix:/workspace/artifacts}") String mountPrefix,
            @Value("${harness.a2a.artifact.keep-artifacts:false}") boolean keepArtifacts,
            V2SandboxConfig.SandboxPropertiesV2 sandboxProps) {
        Path artifactsRoot = Paths.get(workspacePath).toAbsolutePath().resolve("artifacts");
        V2SandboxConfig.SandboxPropertiesV2.Artifacts.Remote remoteCfg =
                sandboxProps.getArtifacts().getRemote();

        ArtifactIo io;
        if (remoteCfg.isEnabled()
                && !remoteCfg.getSshTarget().isBlank()
                && !remoteCfg.getRemoteRoot().isBlank()) {
            io = new SshArtifactIo(
                    remoteCfg.getSshTarget(),
                    remoteCfg.getRemoteRoot(),
                    remoteCfg.getSshOptions(),
                    remoteCfg.getTimeoutSeconds());
            log.info(
                    "ArtifactStore: SshArtifactIo wired - sshTarget={} remoteRoot={}",
                    remoteCfg.getSshTarget(),
                    remoteCfg.getRemoteRoot());
        } else {
            io = new LocalArtifactIo(artifactsRoot);
            log.info("ArtifactStore: LocalArtifactIo wired - artifactsRoot={}", artifactsRoot);
        }
        return new ArtifactStore(artifactsRoot, io, mountPrefix, keepArtifacts);
    }

    // ── Artifact Sweeper (backstop GC for crash/dropped-connection cases) ──

    @Bean
    public ArtifactSweeper artifactSweeper(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.a2a.artifacts.sweeper.max-age-hours:6}") long maxAgeHours,
            @Value("${harness.a2a.artifacts.sweeper.enabled:true}") boolean enabled,
            @Value("${harness.a2a.artifacts.keep:false}") boolean keepArtifacts,
            com.agentscopea2a.v2.alarm.CronFailureAlerter alerter) {
        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        return new ArtifactSweeper(workspace, maxAgeHours, enabled, keepArtifacts, alerter);
    }

    // ── Artifact Access Middleware (v2 Middleware — intercepts tool calls) ──

    @Bean
    public ArtifactAccessMiddleware artifactAccessMiddleware(ArtifactStore artifactStore) {
        // Note: fixedContext is null here; per-request context will be derived from RuntimeContext
        // inside the middleware's onActing() method. For production use with A2A supervisor,
        // the HarnessA2aRunnerV2 creates a per-request ArtifactAccessMiddleware with fixedContext.
        log.info("ArtifactAccessMiddleware: wired (context from RuntimeContext)");
        return new ArtifactAccessMiddleware(artifactStore);
    }

    /**
     * P0-5: Cross-tenant path guard for {@code python_exec}. Sister middleware to
     * {@link ArtifactAccessMiddleware} - scans the {@code code} parameter of
     * {@code python_exec} calls for foreign artifact paths and rewrites them to a failing
     * snippet if found. Wired on both main agent (via HarnessA2aRunnerV2) and subagents
     * declaring python_exec (via SubagentRegistrar).
     */
    @Bean
    public PythonExecAccessMiddleware pythonExecAccessMiddleware(ArtifactStore artifactStore) {
        log.info("PythonExecAccessMiddleware: wired (context from RuntimeContext)");
        return new PythonExecAccessMiddleware(artifactStore);
    }

    // ── Artifact Handoff Hook (v2 Hook — rewrites tool results) ────────────
    // This uses the Hook API (deprecated) because MiddlewareBase.onActing() only intercepts
    // tool calls before execution, not results after. The PostActingEvent provides the
    // complete ToolResultBlock needed for the rewrite. Will migrate to middleware once the
    // framework adds tool-result event interception to the middleware chain.

    @Bean
    @SuppressWarnings("deprecation")
    public ArtifactHandoffHook artifactHandoffHook(ArtifactStore artifactStore) {
        // Note: fixedContext is null; per-request context derived from RuntimeContext
        log.info("ArtifactHandoffHook: wired (context from RuntimeContext)");
        return new ArtifactHandoffHook(artifactStore);
    }

    // ── Python Exec Retry Hook (v2 Hook — annotates failed python_exec results) ──

    @Bean
    @SuppressWarnings("deprecation")
    public PythonExecRetryHook pythonExecRetryHook() {
        log.info("PythonExecRetryHook: wired (priority=13)");
        return new PythonExecRetryHook();
    }

    // ── Tool Call Tracking Hook (v2 Hook — records L1 tool calls via RuntimeContext) ──
    // Uses the Hook API because it needs both PreActing (input) and PostActing (output).
    // Retrieves the per-request ToolCallCollector from RuntimeContext (pushed by the
    // framework via RuntimeContextAware) instead of ThreadLocal, which broke across
    // reactive thread boundaries.

    @Bean
    @SuppressWarnings("deprecation")
    public ToolCallTrackingHook toolCallTrackingHook() {
        log.info("ToolCallTrackingHook: wired (priority=45, RuntimeContextAware-based)");
        return new ToolCallTrackingHook();
    }

    // ── Session Middleware (sanitizes regex in tool call inputs) ──

    @Bean
    public SessionMiddleware sessionMiddleware() {
        log.info("SessionMiddleware: wired (regex sanitization in tool call inputs)");
        return new SessionMiddleware();
    }
}