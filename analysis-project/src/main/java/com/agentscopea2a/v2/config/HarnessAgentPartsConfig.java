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

import com.agentscopea2a.v2.hooks.ArithMentalMathDetectorHook;
import com.agentscopea2a.v2.hooks.ArtifactHandoffHook;
import com.agentscopea2a.v2.hooks.KnowledgeRetrievalHook;
import com.agentscopea2a.v2.hooks.PythonExecRetryHook;
import com.agentscopea2a.v2.hooks.SkillEvolutionHook;
import com.agentscopea2a.v2.hooks.SkillRetrievalHook;
import com.agentscopea2a.v2.hooks.SkillSynthesisHook;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import com.agentscopea2a.v2.middleware.EpisodicRetrievalMiddleware;
import com.agentscopea2a.v2.middleware.MemoryLedgerMirrorMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.ResponseCacheMiddleware;
import com.agentscopea2a.v2.middleware.SessionMiddleware;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the ordered {@link MiddlewareBase} and {@link Hook} lists consumed by
 * {@link com.agentscopea2a.v2.runner.HarnessA2aRunnerV2}.
 *
 * <p>Replaces the inline {@code List<MiddlewareBase> middlewares = new ArrayList<>(...)}
 * and the seven conditional {@code builder.hook(...)} blocks that used to live in the
 * runner's constructor. The runner now just injects {@code List<MiddlewareBase>} and
 * {@code List<Hook>} and forwards them to {@code builder.middlewares(...)}/{@code .hook(...)}.
 *
 * <p>Order matters:
 * <ul>
 *   <li>Middlewares: responseCache -> dimension -> episodic -> artifact -> session ->
 *       ledgerMirror (optional) -> pythonExecGuard (optional)</li>
 *   <li>Hooks: handoff(12) -> retry(13) -> tracking(45) -> retrieval(-50) ->
 *       knowledge(-40) -> synthesis(50) -> evolution(60). Each hook's {@code priority()}
 *       controls actual execution order; this list only controls wiring order so the
 *       log lines are deterministic.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(HarnessRunnerProperties.class)
public class HarnessAgentPartsConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentPartsConfig.class);

    @Bean
    public List<MiddlewareBase> harnessMiddlewares(
            ResponseCacheMiddleware responseCacheMiddleware,
            DimensionStateMiddleware dimensionStateMiddleware,
            EpisodicRetrievalMiddleware episodicRetrievalMiddleware,
            ArtifactAccessMiddleware artifactAccessMiddleware,
            SessionMiddleware sessionMiddleware,
            ObjectProvider<MemoryLedgerMirrorMiddleware> memoryLedgerMirrorProvider,
            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessMiddlewareProvider) {
        List<MiddlewareBase> middlewares = new ArrayList<>(List.of(
                responseCacheMiddleware,
                dimensionStateMiddleware,
                episodicRetrievalMiddleware,
                artifactAccessMiddleware,
                sessionMiddleware
        ));
        MemoryLedgerMirrorMiddleware ledgerMirror = memoryLedgerMirrorProvider.getIfAvailable();
        if (ledgerMirror != null) {
            middlewares.add(ledgerMirror);
            log.info("HarnessAgentPartsConfig: MemoryLedgerMirrorMiddleware wired");
        }
        PythonExecAccessMiddleware pythonExecGuard = pythonExecAccessMiddlewareProvider.getIfAvailable();
        if (pythonExecGuard != null) {
            middlewares.add(pythonExecGuard);
            log.info("HarnessAgentPartsConfig: PythonExecAccessMiddleware wired (P0-5 cross-tenant guard)");
        }
        return middlewares;
    }

    @Bean
    public List<Hook> harnessHooks(
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<SkillRetrievalHook> skillRetrievalHookProvider,
            ObjectProvider<SkillSynthesisHook> skillSynthesisHookProvider,
            ObjectProvider<SkillEvolutionHook> skillEvolutionHookProvider,
            ObjectProvider<KnowledgeRetrievalHook> knowledgeRetrievalHookProvider,
            ObjectProvider<ArithMentalMathDetectorHook> arithMentalMathDetectorProvider) {
        List<Hook> hooks = new ArrayList<>(8);
        ArtifactHandoffHook handoff = artifactHandoffHookProvider.getIfAvailable();
        if (handoff != null) {
            hooks.add(handoff);
            log.info("HarnessAgentPartsConfig: ArtifactHandoffHook wired (priority=12)");
        }
        PythonExecRetryHook retry = pythonExecRetryHookProvider.getIfAvailable();
        if (retry != null) {
            hooks.add(retry);
            log.info("HarnessAgentPartsConfig: PythonExecRetryHook wired (priority=13)");
        }
        ToolCallTrackingHook tracking = toolCallTrackingHookProvider.getIfAvailable();
        if (tracking != null) {
            hooks.add(tracking);
            log.info("HarnessAgentPartsConfig: ToolCallTrackingHook wired (priority=45)");
        }
        SkillRetrievalHook retrieval = skillRetrievalHookProvider.getIfAvailable();
        if (retrieval != null) {
            hooks.add(retrieval);
            log.info("HarnessAgentPartsConfig: SkillRetrievalHook wired (priority=-50)");
        }
        SkillSynthesisHook synthesis = skillSynthesisHookProvider.getIfAvailable();
        if (synthesis != null) {
            hooks.add(synthesis);
            log.info("HarnessAgentPartsConfig: SkillSynthesisHook wired (priority=50)");
        }
        SkillEvolutionHook evolution = skillEvolutionHookProvider.getIfAvailable();
        if (evolution != null) {
            hooks.add(evolution);
            log.info("HarnessAgentPartsConfig: SkillEvolutionHook wired (priority=60)");
        }
        KnowledgeRetrievalHook knowledge = knowledgeRetrievalHookProvider.getIfAvailable();
        if (knowledge != null) {
            hooks.add(knowledge);
            log.info("HarnessAgentPartsConfig: KnowledgeRetrievalHook wired (priority=-40)");
        }
        ArithMentalMathDetectorHook arithDetector = arithMentalMathDetectorProvider.getIfAvailable();
        if (arithDetector != null) {
            hooks.add(arithDetector);
            log.info("HarnessAgentPartsConfig: ArithMentalMathDetectorHook wired (priority=70)");
        }
        return hooks;
    }
}
