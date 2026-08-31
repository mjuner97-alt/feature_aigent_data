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

import com.agentscopea2a.v2.hooks.*;
import com.agentscopea2a.v2.trace.collector.AiChatRestToolCallTrackingToDbHook;
import com.agentscopea2a.v2.verify.VerificationHook;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import com.agentscopea2a.v2.middleware.EpisodicRetrievalMiddleware;
import com.agentscopea2a.v2.middleware.MemoryLedgerMirrorMiddleware;
import com.agentscopea2a.v2.middleware.PerUserMemoryContextMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.ResponseCacheMiddleware;
import com.agentscopea2a.v2.middleware.SessionMiddleware;
import com.agentscopea2a.v2.middleware.ToolCallContentRepairMiddleware;
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

/** 组装 Runner 所需的 Middleware 和 Hook 有序列表 */
@Configuration
@EnableConfigurationProperties(HarnessRunnerProperties.class)
public class HarnessAgentPartsConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentPartsConfig.class);

    @Bean
    public ToolCallContentRepairMiddleware toolCallContentRepairMiddleware() {
        log.info("HarnessAgentPartsConfig: ToolCallContentRepairMiddleware @Bean registered (priority=-100)");
        return new ToolCallContentRepairMiddleware();
    }


    @Bean
    public com.agentscopea2a.v2.middleware.ParentEmitterCaptureMiddleware parentEmitterCaptureMiddleware() {
        log.info("HarnessAgentPartsConfig: ParentEmitterCaptureMiddleware @Bean registered");
        return new com.agentscopea2a.v2.middleware.ParentEmitterCaptureMiddleware();
    }

    @Bean
    public AiChatRestToolCallTrackingToDbHook traceCollectorHook() {
        log.info("HarnessAgentPartsConfig: AiChatRestToolCallTrackingToDbHook @Bean registered (priority=47)");
        return new AiChatRestToolCallTrackingToDbHook();
    }

    @Bean
    public List<MiddlewareBase> harnessMiddlewares(
            ResponseCacheMiddleware responseCacheMiddleware,
            DimensionStateMiddleware dimensionStateMiddleware,
            EpisodicRetrievalMiddleware episodicRetrievalMiddleware,
            ArtifactAccessMiddleware artifactAccessMiddleware,
            SessionMiddleware sessionMiddleware,
            ObjectProvider<PerUserMemoryContextMiddleware> perUserMemoryContextMiddlewareProvider,
            ObjectProvider<MemoryLedgerMirrorMiddleware> memoryLedgerMirrorProvider,
            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessMiddlewareProvider,
            ToolCallContentRepairMiddleware toolCallContentRepairMiddleware) {
        List<MiddlewareBase> middlewares = new ArrayList<>(List.of(
                toolCallContentRepairMiddleware,
                responseCacheMiddleware,
                dimensionStateMiddleware,
                episodicRetrievalMiddleware,
                artifactAccessMiddleware,
                sessionMiddleware
        ));
//        PerUserMemoryContextMiddleware perUserMemory = perUserMemoryContextMiddlewareProvider.getIfAvailable();
//        if (perUserMemory != null) {
//            middlewares.add(perUserMemory);
//            log.info("HarnessAgentPartsConfig: PerUserMemoryContextMiddleware wired (per-user MEMORY.md injection)");
//        }
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
        // ParentEmitterCaptureMiddleware is registered as a top-level @Bean above so
        // Spring's List<MiddlewareBase> auto-collection in HarnessA2aRunnerV2 picks it
        // up. It is NOT added here — adding it here would cause double registration
        // (once as a bean, once inside this list bean), and since the runner injects
        // the auto-collected list (not this list bean), the list-bean copy is dead
        // weight anyway. See parentEmitterCaptureMiddleware() javadoc above.
        return middlewares;
    }

    @Bean
    public List<Hook> harnessHooks(
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<ChatScriptExecResultHook> chatScriptExecResultHookProvider,
            ObjectProvider<SkillSynthesisHook> skillSynthesisHookProvider,
            ObjectProvider<SkillEvolutionHook> skillEvolutionHookProvider,
            ObjectProvider<KnowledgeRetrievalHook> knowledgeRetrievalHookProvider,
            ObjectProvider<ArithMentalMathDetectorHook> arithMentalMathDetectorProvider,
            ObjectProvider<VerificationHook> verificationHookProvider,
            ObjectProvider<AiChatRestToolCallTrackingToDbHook> traceCollectorHookProvider) {
        List<Hook> hooks = new ArrayList<>(10);
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
            log.info("HarnessAgentPartsConfig: ToolCallTrackingHook wired (priority=50)");
        }
        ChatScriptExecResultHook chatScript = chatScriptExecResultHookProvider.getIfAvailable();
        if (chatScript != null) hooks.add(chatScript);
        VerificationHook verification = verificationHookProvider.getIfAvailable();
        if (verification != null) {
            hooks.add(verification);
            log.info("HarnessAgentPartsConfig: VerificationHook wired (priority=46)");
        }

        // Trace 落库 Hook：捕获 Hook 事件完整 payload（LLM 输入/思考/输出、工具入参/返回）到 ClickHouse。
        // priority=47，紧跟 VerificationHook(46)，确保 PostActing 的 toolResult 为最终值。
        // 单例 bean，主 agent 与子 agent（SubagentRegistrar）共用；仅 v1 /ai/chat 创建 TraceSession，v2 no-op。
        AiChatRestToolCallTrackingToDbHook traceCollector = traceCollectorHookProvider.getIfAvailable();
        if (traceCollector != null) {
            hooks.add(traceCollector);
            log.info("HarnessAgentPartsConfig: AiChatRestToolCallTrackingToDbHook wired (priority=47)");
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
