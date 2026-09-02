package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.tools.ToolResultRegistry;
import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.regex.Matcher;

/**
 * /ai/chat 专用的 script_exec 可渲染结果 Hook。
 *
 * <p>只处理 script_exec 返回的 HTML/ECharts 代码块：将原文登记到结果池，
 * 再把完整内容替换成短引用交给模型；最终回答层再根据引用恢复原文。
 * 其他工具、工具明细和普通 SSE 出参均不在本类处理范围内。</p>
 */
@SuppressWarnings("deprecation")
public class ChatScriptExecResultHook implements Hook, RuntimeContextAware {
    /** RuntimeContext 开关：只有 /ai/chat 显式设置为 true 时才执行本 Hook。 */
    public static final String ENABLED_CTX_KEY = "chatScriptExecResultHook.enabled";
    /** 当前请求已登记的可渲染结果引用，供最终回答层直接展开。 */
    public static final String REFERENCES_CTX_KEY = "chatScriptExecResultHook.references";
    public static final String REQUEST_ID_CTX_KEY = "chatScriptExecResultHook.requestId";
    /** Original fenced blocks collected for independent Skill Job report persistence. */
    public static final String RAW_BLOCKS_CTX_KEY = "chatScriptExecResultHook.rawBlocks";
    private final ToolResultRegistry registry;
    private volatile RuntimeContext currentContext;

    public ChatScriptExecResultHook(ToolResultRegistry registry) { this.registry = registry; }

    /**
     * Machine-readable reference passed through the model context. It is expanded
     * only at the final response boundary, so no human-facing placeholder text is
     * sent to the client.
     */
    public static String referenceMarker(String ref) {
        return String.format(ToolResultRegistry.MARKER_TEMPLATE, ref);
    }

    /** Text returned to the model in place of an internal result reference. */
    public static String modelVisiblePlaceholder() {
        return "[系统内部：已接管可渲染内容。请勿复述该内容、生成代码块或输出内部引用 ID；仅根据其余执行结果回答用户。]";
    }

    /** Converts fenced chart output to the tag format consumed by report rendering. */
    public static String renderableContent(String block) {
        if (block == null) return "";
        String trimmedBlock = block.trim();
        if (!trimmedBlock.startsWith("```")) {
            return trimmedBlock;
        }
        int newline = block.indexOf('\n');
        String language = newline >= 0 ? block.substring(3, newline).trim().toLowerCase() : "";
        String content = stripFence(block);
        if ("echart".equals(language) || "echarts".equals(language)) {
            return "<echarts>\n" + content + "\n</echarts>";
        }
        if ("html".equals(language) || "htm".equals(language)) {
            // HTML is cached as-is after removing the Markdown fence; do not add an echarts tag.
            return content;
        }
        return content;
    }
    @Override public int priority() { return 50; }
    @Override public void setRuntimeContext(RuntimeContext context) { currentContext = context; }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return HookRuntimeContext.resolve().doOnNext(ctx -> process(event, ctx))
                .switchIfEmpty(Mono.fromRunnable(() -> { if (currentContext != null) process(event, currentContext); }))
                .then(Mono.just(event));
    }

    /** 仅处理启用开关的 PostActing script_exec 事件。 */
    private void process(HookEvent event, RuntimeContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(ENABLED_CTX_KEY)) || !(event instanceof PostActingEvent post)) return;
        ToolUseBlock use = post.getToolUse();
        ToolResultBlock result = post.getToolResult();
        if (use == null || result == null || !"script_exec".equals(use.getName())) return;
        // 工具结果可能包含普通文本和 fenced HTML/ECharts 代码块，先拼成完整文本再解析。
        String output = extractText(result.getOutput());
        Matcher matcher = ScriptExecOutputExtractor.RENDERABLE_BLOCK_PATTERN.matcher(output);
        StringBuffer rewritten = new StringBuffer();
        List<String> refs = new java.util.ArrayList<>();
        boolean found = false;
        // 每个可渲染代码块独立登记，避免多个图表共用同一个引用。
        while (matcher.find()) {
            found = true;
            Object existing = ctx.get(RAW_BLOCKS_CTX_KEY);
            List<String> rawBlocks = new java.util.ArrayList<>();
            if (existing instanceof List<?> values) values.stream().filter(String.class::isInstance).map(String.class::cast).forEach(rawBlocks::add);
            rawBlocks.add(matcher.group());
            ctx.put(RAW_BLOCKS_CTX_KEY, List.copyOf(rawBlocks));
            String ref = registry.register(ctx.getSessionId(), use.getId(), use.getName(), renderableContent(matcher.group()));
            refs.add(ref);
            Object requestId = ctx.get(REQUEST_ID_CTX_KEY);
            if (requestId instanceof String id) registry.addRequestRef(id, ref);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(modelVisiblePlaceholder()));
        }
        if (!found) return;
        matcher.appendTail(rewritten);
        // 同一请求可多次调用 script_exec，不能让后一次调用覆盖前一次图表引用。
        List<String> allRefs = new java.util.ArrayList<>();
        Object existingRefs = ctx.get(REFERENCES_CTX_KEY);
        if (existingRefs instanceof List<?> values) {
            values.stream().filter(String.class::isInstance).map(String.class::cast).forEach(allRefs::add);
        }
        allRefs.addAll(refs);
        ctx.put(REFERENCES_CTX_KEY, List.copyOf(allRefs));
        // PostActingEvent 同时更新两个字段，确保后续 Agent 消息和框架工具消息都看到替换结果。
        ToolResultBlock replacement = ToolResultBlock.of(use.getId(), use.getName(),
                List.of(TextBlock.builder().text(rewritten.toString()).build()));
        post.setToolResult(replacement);
        post.setToolResultMsg(Msg.builder().role(MsgRole.TOOL).content(replacement).build());
    }

    private static String extractText(List<?> blocks) {
        StringBuilder text = new StringBuilder();
        if (blocks != null) for (Object block : blocks)
            if (block instanceof TextBlock tb && tb.getText() != null) text.append(tb.getText());
        return text.toString();
    }

    /** 去掉 ```html / ```echarts 外层围栏，只把实际内容写入结果池。 */
    private static String stripFence(String block) {
        int newline = block.indexOf('\n');
        String trimmed = (newline >= 0 ? block.substring(newline + 1) : block).trim();
        return trimmed.endsWith("```") ? trimmed.substring(0, trimmed.length() - 3).trim() : trimmed;
    }
}
