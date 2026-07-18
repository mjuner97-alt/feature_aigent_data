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

import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.hooks.KnowledgeRetrievalHook;
import com.agentscopea2a.v2.hooks.SkillEvolutionHook;
import com.agentscopea2a.v2.hooks.SkillRetrievalHook;
import com.agentscopea2a.v2.hooks.SkillSynthesisHook;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.MetricClassificationService;
import com.agentscopea2a.v2.skills.SkillCandidateRepository;
import com.agentscopea2a.v2.skills.SkillDistiller;
import com.agentscopea2a.v2.skills.SkillEvolutionRunner;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillSynthesisRunner;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import com.agentscopea2a.v2.skills.SkillVectorIndexVisibilityFilter;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.skill.curator.LocalApprovalGate;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v2 SkillCurator pipeline configuration.
 *
 * <p>Enables the v2 skill management lifecycle:
 * propose → promote → usage → audit → security → curator.
 * Uses {@link LocalApprovalGate} for HITL (human-in-the-loop) skill promotion.
 *
 * <p>Also wires the {@link SkillVectorIndex} (PR3 vector/fingerprint lookup) and
 * {@link SkillVectorIndexVisibilityFilter} (bridges the index into the curator pipeline).
 */
@Configuration
public class V2SkillConfig {

    private static final Logger log = LoggerFactory.getLogger(V2SkillConfig.class);

    @Bean
    public SkillManageConfig skillManageConfig() {
        return SkillManageConfig.defaults();
    }

    @Bean
    public SkillCuratorConfig skillCuratorConfig() {
        return SkillCuratorConfig.builder()
                .staleAfterDays(30)
                .archiveAfterDays(90)
                .build();
    }

    @Bean
    public LocalApprovalGate localApprovalGate() {
        return new LocalApprovalGate();
    }

    @Bean
    public SkillVectorIndex skillVectorIndex(
            DataSource dataSource,
            @Value("${harness.skills.retrieval.cache-enabled:true}") boolean cacheEnabled,
            @Value("${harness.skills.retrieval.cache-refresh-seconds:60}") int cacheRefreshSeconds) {
        log.info("SkillVectorIndex: cacheEnabled={}, cacheRefreshSeconds={}", cacheEnabled, cacheRefreshSeconds);
        return new SkillVectorIndex(dataSource, cacheEnabled, cacheRefreshSeconds);
    }

    @Bean
    public SkillVisibilityFilter skillVectorIndexVisibilityFilter(
            SkillVectorIndex skillVectorIndex,
            EmbeddingClient embeddingClient,
            @Value("${harness.skills.retrieval.top-k:5}") int topK,
            @Value("${harness.skills.retrieval.min-cosine:0.6}") float minCosine) {
        log.info("SkillVectorIndexVisibilityFilter: topK={}, minCosine={}", topK, minCosine);
        return new SkillVectorIndexVisibilityFilter(skillVectorIndex, embeddingClient, topK, minCosine);
    }

    /**
     * PR3 skill-retrieval hook - retrieves matched skills from {@code skills-auto/} and
     * {@code skills-user/} into the system prompt at PreCall. v2 port of the v1 hook,
     * using {@link RuntimeContextAware} for per-call context instead of constructor injection.
     *
     * <p>This is the ONLY path that injects skills-auto/ and skills-user/ skills into the
     * prompt. The {@link SkillVectorIndexVisibilityFilter} above only filters JAR-builtin
     * skills (data_primitives, tool_index from {@code skills/}), it does NOT retrieve from
     * skills-auto/ or skills-user/.
     */
    @Bean
    public SkillRetrievalHook skillRetrievalHook(
            SkillVectorIndex skillVectorIndex,
            SkillIndexRepository indexRepo,
            FingerprintCalculator fingerprintCalculator,
            EmbeddingClient embeddingClient,
            ObjectProvider<EpisodicMemory> episodicMemoryProvider,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.skills.retrieval.enabled:true}") boolean enabled,
            @Value("${harness.skills.retrieval.top-k:5}") int topK,
            @Value("${harness.skills.retrieval.min-cosine:0.6}") float minCosine) {
        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        Path skillsAutoDir = workspace.resolve("skills-auto");
        Path skillsUserDir = workspace.resolve("skills-user");
        EpisodicMemory episodicMemory = episodicMemoryProvider.getIfAvailable();
        log.info("SkillRetrievalHook: enabled={}, topK={}, minCosine={}, episodicMemory={}",
                enabled, topK, minCosine, episodicMemory != null ? "wired" : "null");
        return new SkillRetrievalHook(
                skillVectorIndex,
                indexRepo,
                fingerprintCalculator,
                embeddingClient,
                episodicMemory,
                skillsAutoDir,
                skillsUserDir,
                enabled,
                topK,
                minCosine);
    }

    /**
     * Knowledge dynamic retrieval hook - injects files from {@code knowledge-dynamic/} into the
     * system prompt when the user's question matches keywords in {@code knowledge-index.yaml}.
     * v2 port of the v1 hook at {@code com.agentscopea2a.harness.hooks.KnowledgeRetrievalHook}
     * (Maven-excluded). {@code knowledge/} is always loaded by the JAR-internal
     * {@code WorkspaceContextHook}; {@code knowledge-dynamic/} files are on-demand only.
     */
    @Bean
    public KnowledgeRetrievalHook knowledgeRetrievalHook(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.knowledge.retrieval.enabled:true}") boolean enabled) {
        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        Path knowledgeDynamicDir = workspace.resolve("knowledge-dynamic");
        log.info("KnowledgeRetrievalHook: enabled={}, knowledgeDynamicDir={}", enabled, knowledgeDynamicDir);
        return new KnowledgeRetrievalHook(knowledgeDynamicDir, enabled);
    }

    /**
     * PR2 - automatic skill synthesis (cache-MISS path). PreCall hook that bumps the
     * {@code skill_candidate} row for the user question's fingerprint and dispatches async
     * distillation when the threshold is crossed. v2 port of the v1
     * {@code com.agentscopea2a.harness.hooks.SkillSynthesisHook} (Maven-excluded).
     *
     * <p>The cache-HIT path was deprecated (ResponseCache HIT disabled); only the MISS path
     * is migrated. Without this hook, {@code skill_candidate.hit_count} never increments on
     * chat requests and online bump-to-distill never fires (only nightly digestion can).
     */
    @Bean
    public SkillSynthesisHook skillSynthesisHook(
            SkillSynthesisRunner skillSynthesisRunner,
            MetricClassificationService metricClassificationService,
            FingerprintCalculator fingerprintCalculator) {
        log.info(
                "SkillSynthesisHook: auto-synth enabled={}, threshold={}",
                skillSynthesisRunner.enabled(),
                skillSynthesisRunner.threshold());
        return new SkillSynthesisHook(
                skillSynthesisRunner,
                metricClassificationService,
                fingerprintCalculator);
    }

    /**
     * PR4 - failure-feedback closed loop. PreCall+PostCall hook that credits success/failure
     * counts to retrieved skills based on python_exec retry signals and cross-turn user
     * rejection keywords. When failure_rate exceeds the threshold, dispatches async evolve
     * via {@link SkillEvolutionRunner}. v2 port of the v1
     * {@code com.agentscopea2a.harness.hooks.SkillEvolutionHook} (Maven-excluded).
     *
     * <p>Without this hook, {@code skill_index.success_count}/{@code failure_count} never
     * increment on chat requests and online PR4 evolution never fires (only nightly digestion
     * can trigger evolution, and only when no user skill covers the same fingerprint).
     */
    @Bean
    public SkillEvolutionHook skillEvolutionHook(
            SkillEvolutionRunner skillEvolutionRunner,
            FingerprintCalculator fingerprintCalculator,
            SkillVectorIndex skillVectorIndex,
            @Value("${harness.skills.evolution.enabled:false}") boolean evolutionEnabled,
            @Value("${harness.skills.evolution.fail-rate-evolve:0.3}") double failRateEvolve,
            @Value("${harness.skills.evolution.min-uses-evolve:5}") int minUsesEvolve,
            @Value("${harness.skills.evolution.rejection-keywords:不对,错了,重算,重新,不是这样,不正确}") String rejectionKeywords) {
        log.info(
                "SkillEvolutionHook: enabled={}, failRateEvolve={}, minUsesEvolve={}, rejectionKeywords={}",
                evolutionEnabled,
                failRateEvolve,
                minUsesEvolve,
                rejectionKeywords);
        return new SkillEvolutionHook(
                skillEvolutionRunner,
                rejectionKeywords,
                fingerprintCalculator,
                skillVectorIndex);
    }

    // ── Stage 7: Skill candidate + metric classification + fingerprint ─────────

    @Bean
    public SkillCandidateRepository skillCandidateRepository(DataSource dataSource) {
        SkillCandidateRepository repo = new SkillCandidateRepository(dataSource);
        repo.initSchema();
        return repo;
    }

    @Bean
    public MetricClassificationService metricClassificationService(
            SkillCandidateRepository candidateRepo,
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String lightApiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String lightBaseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String lightModelName,
            @Value("${harness.skills.metric-classification.enabled:true}") boolean enabled,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        Model lightModel = null;
        if (enabled) {
            try {
                lightModel = OpenAIChatModel.builder()
                        .apiKey(lightApiKey)
                        .baseUrl(lightBaseUrl)
                        .modelName(lightModelName)
                        .stream(true)
                        .build();
                log.info("MetricClassificationService: light model '{}' initialized", lightModelName);
            } catch (Exception e) {
                log.warn("MetricClassificationService: failed to create light model: {}", e.getMessage());
            }
        }
        Path wp = Paths.get(workspacePath).toAbsolutePath();
        MetricClassificationService svc = new MetricClassificationService(candidateRepo, lightModel, enabled, wp);
        svc.init();
        return svc;
    }

    @Bean
    public FingerprintCalculator fingerprintCalculator(
            DimensionStateManager dimensionStateManager,
            MetricClassificationService metricClassificationService) {
        return new FingerprintCalculator(dimensionStateManager, metricClassificationService);
    }

    // ── Stage 8: Skill distiller (foundation for synthesis/evolution/digestion) ─────────

    /**
     * Distills an exemplar user question (+ optional recent trace) into the body of a SKILL.md.
     *
     * <p>Stage 8: migrated from {@code com.agentscopea2a.harness.skills.SkillDistiller}
     * (Maven-excluded v1 package) to v2. Uses the light-classifier model (same as
     * {@link #metricClassificationService}) to keep LLM cost low - distillation runs
     * off the request hot path so latency doesn't matter.
     */
    @Bean
    public SkillDistiller skillDistiller(
            MetricClassificationService metricClassificationService,
            ObjectProvider<EpisodicMemory> episodicMemoryProvider,
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String lightApiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String lightBaseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String lightModelName,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        Model lightModel = OpenAIChatModel.builder()
                .apiKey(lightApiKey)
                .baseUrl(lightBaseUrl)
                .modelName(lightModelName)
                .stream(true)
                .build();
        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        return new SkillDistiller(lightModel, episodicMemoryProvider, metricClassificationService, workspace);
    }

    /**
     * Shared "count -> maybe distill" pipeline for both the Cache MISS path
     * and Cache HIT path. Stage 8: migrated from {@code com.agentscopea2a.harness.skills.SkillSynthesisRunner}
     * (Maven-excluded v1 package) to v2.
     */
    @Bean
    public SkillSynthesisRunner skillSynthesisRunner(
            SkillCandidateRepository candidateRepo,
            SkillIndexRepository indexRepo,
            SkillDistiller skillDistiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<EpisodicMemory> episodicMemoryProvider,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.skills.auto-synth.enabled:false}") boolean enabled,
            @Value("${harness.skills.auto-synth.threshold:3}") int hitThreshold,
            @Value("${harness.skills.auto-synth.via-subagent:true}") boolean viaSubagent) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-auto");
        return new SkillSynthesisRunner(
                candidateRepo,
                indexRepo,
                skillDistiller,
                vectorIndexProvider,
                embeddingClientProvider,
                episodicMemoryProvider,
                skillsDir,
                enabled,
                hitThreshold,
                viaSubagent);
    }

    /**
     * PR4 - failure-feedback closed loop. Stage 8: migrated from
     * {@code com.agentscopea2a.harness.skills.SkillEvolutionRunner} (Maven-excluded v1 package) to v2.
     */
    @Bean
    public SkillEvolutionRunner skillEvolutionRunner(
            SkillIndexRepository indexRepo,
            SkillDistiller skillDistiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            DataSource dataSource,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.skills.evolution.enabled:false}") boolean enabled,
            @Value("${harness.skills.evolution.fail-rate-evolve:0.3}") double failRateEvolve,
            @Value("${harness.skills.evolution.fail-rate-blacklist:0.6}") double failRateBlacklist,
            @Value("${harness.skills.evolution.min-uses-evolve:5}") int minUsesEvolve,
            @Value("${harness.skills.evolution.min-uses-blacklist:10}") int minUsesBlacklist) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-auto");
        return new SkillEvolutionRunner(
                indexRepo,
                vectorIndexProvider,
                skillDistiller,
                embeddingClientProvider,
                dataSource,
                skillsDir,
                enabled,
                failRateEvolve,
                failRateBlacklist,
                minUsesEvolve,
                minUsesBlacklist);
    }
}