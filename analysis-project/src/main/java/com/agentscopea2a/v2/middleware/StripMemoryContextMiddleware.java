package com.agentscopea2a.v2.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 配置开关 {@code harness.a2a.memory.context-inject.enabled=false} 时启用：
 * 在模型调用前从 system 消息中剔除框架注入的 {@code <memory_context>...</memory_context>} 段。
 *
 * <p>为什么挂在 onModelCall 而不是 onSystemPrompt：框架在 {@code HarnessAgent.build()}
 * 内部才注册 WorkspaceContextMiddleware（晚于所有用户中间件），而
 * {@code applySystemPromptMiddlewares} 按注册顺序链式执行 onSystemPrompt——
 * 用户中间件的 onSystemPrompt 先跑，此时 memory_context 还没注入，剥离无效。
 * onModelCall 在 system prompt 组装完成之后触发，是可靠的剥离点。
 * AGENTS.md / domain_knowledge_context 等其他 loaded_context 段不受影响。
 *
 * <p>动机：shared-container 模式下 WorkspaceManager.readMemoryMd 读的是共享容器根
 * /workspace/MEMORY.md（跨用户串扰源），新 session 不应携带历史记忆。
 * 容器根文件不动，把开关改回 true 即可恢复注入。
 */
public class StripMemoryContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(StripMemoryContextMiddleware.class);

    private static final String OPEN_TAG = "<memory_context>";
    /** 已闭合段（含段尾空白）。 */
    private static final Pattern CLOSED_SECTION = Pattern.compile("<memory_context>[\\s\\S]*?</memory_context>\\s*");
    /** 无闭合标签容错（被截断的 prompt）：从开标签剥到结尾。 */
    private static final Pattern UNCLOSED_TAIL = Pattern.compile("<memory_context>[\\s\\S]*$");

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext runtimeContext, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.messages();
        boolean changed = false;
        List<Msg> rewritten = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            Msg stripped = stripIfSystemMsg(msg);
            if (stripped != msg) {
                changed = true;
            }
            rewritten.add(stripped);
        }
        if (!changed) {
            return next.apply(input);
        }
        log.info("StripMemoryContextMiddleware: removed <memory_context> from system prompt ({} msgs scanned)",
                messages.size());
        return next.apply(new ModelCallInput(rewritten, input.tools(), input.options(), input.model()));
    }

    static Msg stripIfSystemMsg(Msg msg) {
        if (msg == null || msg.getRole() != MsgRole.SYSTEM) {
            return msg;
        }
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg;
        }
        boolean changed = false;
        List<ContentBlock> rewritten = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && text.contains(OPEN_TAG)) {
                    String stripped = stripMemoryContext(text);
                    if (!stripped.equals(text)) {
                        rewritten.add(TextBlock.builder().text(stripped).build());
                        changed = true;
                        continue;
                    }
                }
            }
            rewritten.add(block);
        }
        if (!changed) {
            return msg;
        }
        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(rewritten)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
                .build();
    }

    static String stripMemoryContext(String text) {
        String out = CLOSED_SECTION.matcher(text).replaceAll("");
        return UNCLOSED_TAIL.matcher(out).replaceAll("");
    }
}
