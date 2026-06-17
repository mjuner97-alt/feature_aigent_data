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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

// 本地影子覆盖 JAR 中的 MemoryFlushHook(classpath 优先)。
// 关键差异:
//  1. PostCall 不再触发 flushMemories 的 LLM 抽取(原版每次响应都一次完整 LLM 往返)。
//     长期回忆改由 EpisodicLongTermMemoryAdapter (MySQL FTS) 承担。
//  2. offloadMessages 同步执行(必须在 sandbox 调用上下文存活期间完成,
//     否则 WorkspaceManager.getFilesystem() 在请求结束后会抛 "No active sandbox")。
public class MemoryFlushHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushHook.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private RuntimeContext runtimeContext;

    public MemoryFlushHook(WorkspaceManager workspaceManager, Model model) {
        this.workspaceManager = workspaceManager;
        this.model = model;
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent) {
            offloadInline(event.getAgent());
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 5;
    }

    private void offloadInline(Agent agent) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return;
        }

        Memory memory = reActAgent.getMemory();
        List<Msg> live = memory.getMessages();
        if (live.isEmpty()) {
            return;
        }

        String agentId = agent.getName();
        String sessionId =
                runtimeContext != null && runtimeContext.getSessionId() != null
                        ? runtimeContext.getSessionId()
                        : "default";

        try {
            new MemoryFlushManager(workspaceManager, model)
                    .offloadMessages(live, agentId, sessionId);
        } catch (Exception e) {
            log.warn(
                    "Message offload failed for agent={}, session={}: {}",
                    agentId,
                    sessionId,
                    e.getMessage());
        }
    }
}
