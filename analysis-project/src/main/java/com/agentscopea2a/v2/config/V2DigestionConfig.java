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

import com.agentscopea2a.v2.digestion.MemoryDigestionService;
import com.agentscopea2a.v2.digestion.SkillFlowEvolver;
import com.agentscopea2a.v2.memory.MemoryHydrator;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.MetricClassificationService;
import com.agentscopea2a.v2.skills.SkillDistiller;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillSynthesisRunner;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v2 nightly digestion pipeline configuration.
 *
 * <p>Wires the 4-phase skill evolution / MEMORY.md consolidation pipeline:
 * <ol>
 *   <li>{@link SkillFlowEvolver} - Phase 3 (evolve/distill dispatch)</li>
 *   <li>{@link MemoryDigestionService} - nightly orchestrator (CleanLedger -> MineTraces
 *       -> EvolveSkills -> ConsolidateMemory)</li>
 * </ol>
 *
 * <p>Both beans are gated by {@code harness.a2a.memory.digestion.enabled=true}.
 * The {@link MysqlMemoryStore} and {@link MemoryHydrator} beans are themselves gated
 * by {@code harness.a2a.memory.mysql-mirror.enabled=true} (declared in {@link V2MemoryConfig}).
 *
 * <p>Stage 8: migrated from {@code com.agentscopea2a.agent.memory.digestion.*} (Maven-excluded
 * v1 package) to v2. The {@code @Component}/{@code @ConditionalOnProperty}/{@code @Value}
 * annotations have been removed from the classes themselves; this config class enforces
 * the conditions explicitly via {@code @ConditionalOnProperty} on the bean methods.
 */
@Configuration
public class V2DigestionConfig {

    private static final Logger log = LoggerFactory.getLogger(V2DigestionConfig.class);

    /**
     * Phase 3 of the nightly digestion pipeline: evaluates pending trace groups and dispatches
     * skill evolution or distillation.
     *
     * <p>Only wired when {@code harness.a2a.memory.digestion.enabled=true} AND the
     * {@link MysqlMemoryStore} bean exists (which itself requires
     * {@code harness.a2a.memory.mysql-mirror.enabled=true}).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.digestion",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public SkillFlowEvolver skillFlowEvolver(
            SkillIndexRepository indexRepo,
            SkillDistiller distiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            DataSource dataSource,
            SkillSynthesisRunner synthesisRunner,
            FingerprintCalculator fingerprintCalc,
            MetricClassificationService metricClassifier,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.a2a.memory.digestion.via-subagent:true}") boolean viaSubagent,
            @Value("${harness.a2a.memory.digestion.min-traces:5}") int minTraces) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-auto");
        return new SkillFlowEvolver(
                indexRepo,
                distiller,
                vectorIndexProvider,
                embeddingClientProvider,
                dataSource,
                skillsDir,
                synthesisRunner,
                fingerprintCalc,
                metricClassifier,
                viaSubagent,
                minTraces);
    }

    /**
     * Nightly digestion scheduler. Runs once per night at the configured cron expression
     * (default 21:09) and orchestrates the 4-phase pipeline for each active user.
     *
     * <p>Uses a light-classifier model for the consolidation LLM call (same model as
     * {@link V2SkillConfig#metricClassificationService} - latency doesn't matter since
     * this runs off the request hot path).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.digestion",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public MemoryDigestionService memoryDigestionService(
            DataSource dataSource,
            MysqlMemoryStore store,
            MemoryHydrator hydrator,
            SkillIndexRepository indexRepo,
            SkillDistiller distiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<SkillFlowEvolver> evolverProvider,
            FingerprintCalculator fingerprintCalc,
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String lightApiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String lightBaseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String lightModelName,
            @Value("${harness.a2a.memory.digestion.cron:0 9 21 * * *}") String cronExpression,
            @Value("${harness.a2a.memory.digestion.batch-size:50}") int batchSize,
            @Value("${harness.a2a.memory.digestion.episodic-retention-days:30}") int episodicRetentionDays,
            @Value("${harness.a2a.memory.digestion.ledger-retention-days:90}") int ledgerRetentionDays,
            @Value("${harness.a2a.memory.digestion.summary-max-length:200}") int summaryMaxLength,
            @Value("${harness.a2a.memory.digestion.enabled:false}") boolean enabled,
            @Value("${harness.a2a.memory.digestion.episodic-table-name:QualitySupervisor_episodic_memory}")
                    String episodicTableName,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        Model lightModel = OpenAIChatModel.builder()
                .apiKey(lightApiKey)
                .baseUrl(lightBaseUrl)
                .modelName(lightModelName)
                .stream(true)
                .build();
        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        MemoryDigestionService svc = new MemoryDigestionService(
                dataSource,
                store,
                hydrator,
                lightModel,
                indexRepo,
                vectorIndexProvider,
                distiller,
                embeddingClientProvider,
                evolverProvider,
                fingerprintCalc,
                cronExpression,
                batchSize,
                episodicRetentionDays,
                ledgerRetentionDays,
                summaryMaxLength,
                enabled,
                episodicTableName,
                workspace);
        svc.announce();
        return svc;
    }
}
