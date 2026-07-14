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
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.MetricClassificationService;
import com.agentscopea2a.v2.skills.SkillCandidateRepository;
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
}