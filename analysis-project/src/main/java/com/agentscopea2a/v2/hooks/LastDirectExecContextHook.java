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
package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.skills.DirectSkillExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Injects the last direct-execution snapshot into the system prompt so the LLM can
 * resolve follow-up turns ("9月的呢") on the normal LLM chain.
 *
 * <p>docs/202610/skill-direct-exec-followup-plan.md §3.3 (v2 拍板: 追问轮走 LLM,
 * Java 只做上下文补给, 不做执行决策). Direct-exec short-circuit rounds do NOT start
 * the agent, so the previous turn's question/params/result are absent from the LLM
 * conversation history — this block is the only source of that context:
 * <ul>
 *   <li>{@code question} — the only line carrying metric semantics;</li>
 *   <li>{@code params} — the already-filled actual arguments (defaults included),
 *       so a weak model copies and replaces one key;</li>
 *   <li>{@code result} — truncated stdout digest, enabling "9月和7月比呢" without re-running.</li>
 * </ul>
 *
 * <p>No snapshot → no injection (byte-identical with current behaviour). Injection
 * failures are caught and never block a request (same discipline as VersionPlanKnowledgeHook).
 */
public class LastDirectExecContextHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(LastDirectExecContextHook.class);

    static final String OPEN_TAG = "\n\n<last_direct_exec>\n";
    static final String CLOSE_TAG = "</last_direct_exec>\n";

    private final DirectSkillExecutor executor;
    private final boolean enabled;

    /** Pushed by the framework via {@link RuntimeContextAware} before each call. */
    private volatile RuntimeContext currentContext;

    public LastDirectExecContextHook(DirectSkillExecutor executor, boolean enabled) {
        this.executor = executor;
        this.enabled = enabled;
    }

    @Override
    public int priority() {
        // Next to VersionPlanKnowledgeHook (-45); pure system-prompt append, order irrelevant
        return -44;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled || !(event instanceof PreCallEvent e)) {
            return Mono.just(event);
        }
        try {
            inject(e);
        } catch (Exception ex) {
            // Injection must never block a request
            log.warn("LastDirectExecContextHook injection skipped: {}", ex.getMessage());
        }
        return Mono.just(event);
    }

    private void inject(PreCallEvent event) {
        RuntimeContext ctx = currentContext;
        if (ctx == null || executor == null) {
            return;
        }
        DirectSkillExecutor.LastDirectExec last =
                executor.lastExec(ctx.getUserId(), ctx.getSessionId());
        if (last == null) {
            return;
        }
        StringBuilder block = new StringBuilder(OPEN_TAG);
        block.append("上一轮用户问题及直接执行情况（用户追问时复用）：\n");
        block.append("question: ").append(last.question()).append('\n');
        block.append("skill: ").append(last.skillName()).append('\n');
        block.append("script_id: ").append(last.scriptId()).append('\n');
        block.append("params: ").append(last.params()).append('\n');
        if (last.resultDigest() != null && !last.resultDigest().isBlank()) {
            block.append("result: ").append(last.resultDigest().replace("\n", "\\n")).append('\n');
        }
        block.append("若用户本轮是追问（只变更部分维度，如时间、部门），复用未变更参数、仅替换本轮提及的维度，")
                .append("调用 script_exec(script_id=上面值, params=变更后参数) 执行并汇报结果；")
                .append("对比类追问可直接引用 result 中的上一轮数据；")
                .append("若用户问了新问题，忽略本块。\n");
        block.append(CLOSE_TAG);
        event.appendSystemContent(block.toString());
        log.info("LastDirectExecContextHook injected snapshot: skill={} scriptId={}",
                last.skillName(), last.scriptId());
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentContext = context;
    }
}
