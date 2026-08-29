package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.context.ContextBudgetProperties;
import com.agentscopea2a.v2.context.ContextSizeEstimator;
import com.agentscopea2a.v2.context.ContextSizeSnapshot;
import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactRef;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import org.springframework.core.annotation.Order;

import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Observes and enforces the model-input budget without rewriting LLM output. */
@Order(0)
public class ContextBudgetMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(ContextBudgetMiddleware.class);
    private final ContextBudgetProperties properties;
    private final ToolResultTruncationMiddleware toolResultTruncation;
    private final ArtifactStore artifactStore;
    private final Set<String> artifactTools;
    private final int maxLatestToolTokens;

    public ContextBudgetMiddleware(ContextBudgetProperties properties,
                                   ToolResultTruncationMiddleware toolResultTruncation) {
        this(properties, toolResultTruncation, null, Set.of(), properties.getMaxLatestToolTokens());
    }

    public ContextBudgetMiddleware(ContextBudgetProperties properties,
                                   ToolResultTruncationMiddleware toolResultTruncation,
                                   ArtifactStore artifactStore, Set<String> artifactTools,
                                   int maxLatestToolTokens) {
        this.properties = properties;
        this.toolResultTruncation = toolResultTruncation;
        this.artifactStore = artifactStore;
        this.artifactTools = artifactTools == null ? Set.of() : Set.copyOf(artifactTools);
        this.maxLatestToolTokens = Math.max(1, maxLatestToolTokens);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<String> toolNames = input == null || input.tools() == null
                ? List.of()
                : input.tools().stream()
                        .filter(Objects::nonNull)
                        .map(tool -> tool.getName())
                        .filter(Objects::nonNull)
                        .toList();
        log.info("LLM request tools: count={}, names={}", toolNames.size(), toolNames);
        if (!properties.isEnabled()) return next.apply(input);
        ContextSizeSnapshot snapshot = ContextSizeEstimator.estimate(input);
        int budget = Math.max(1, properties.getMaxInputTokens());
        double ratio = snapshot.estimatedInputTokens() / (double) budget;
        ReasoningInput effectiveInput = input;
        if (ratio >= properties.getWarnRatio() && toolResultTruncation != null) {
            effectiveInput = toolResultTruncation.compactInput(input);
            snapshot = ContextSizeEstimator.estimate(effectiveInput);
            ratio = snapshot.estimatedInputTokens() / (double) budget;
        }
        effectiveInput = compactLatestToolResult(effectiveInput, ctx);
        snapshot = ContextSizeEstimator.estimate(effectiveInput);
        ratio = snapshot.estimatedInputTokens() / (double) budget;
        if (ratio >= properties.getHardRatio()) {
            effectiveInput = compactOldMessages(effectiveInput);
            snapshot = ContextSizeEstimator.estimate(effectiveInput);
            ratio = snapshot.estimatedInputTokens() / (double) budget;
        }
        if (ratio >= properties.getHardRatio()) {
            log.error("Context budget exceeded: tokens={}, budget={}, messages={}, toolResultChars={}, largestBlockChars={}",
                    snapshot.estimatedInputTokens(), budget, snapshot.messageCount(),
                    snapshot.toolResultChars(), snapshot.largestBlockChars());
            return Flux.error(new ContextBudgetExceededException(snapshot, budget));
        }
        if (ratio >= properties.getWarnRatio()) {
            log.warn("Context budget warning: tokens={}, budget={}, ratio={}, messages={}, toolResultChars={}",
                    snapshot.estimatedInputTokens(), budget, ratio, snapshot.messageCount(), snapshot.toolResultChars());
        }
        return next.apply(effectiveInput);
    }

    /** Replace an oversized latest plain-text tool result with a tenant-isolated artifact reference. */
    ReasoningInput compactLatestToolResult(ReasoningInput input, RuntimeContext ctx) {
        if (artifactStore == null || input == null || input.messages() == null) return input;
        List<Msg> messages = input.messages();
        for (int mi = messages.size() - 1; mi >= 0; mi--) {
            Msg msg = messages.get(mi);
            if (msg == null || msg.getContent() == null) continue;
            for (ContentBlock block : msg.getContent()) {
                if (!(block instanceof ToolResultBlock result) || result.getName() == null
                        || !artifactTools.contains(result.getName())) continue;
                String text = extractText(result);
                if (text.isBlank() || estimateTokens(text.length()) <= maxLatestToolTokens) return input;
                ArtifactRef ref = artifactStore.saveText(ArtifactContext.from(ctx), result.getName(), text);
                ToolResultBlock replacement = new ToolResultBlock(result.getId(), result.getName(),
                        List.of(TextBlock.builder().text(result.getName() + " 结果过大，完整内容已保存为 artifact：\n"
                                + ref.agentPath()).build()), result.getMetadata(), result.getState());
                List<ContentBlock> content = new ArrayList<>(msg.getContent());
                content.set(content.indexOf(block), replacement);
                List<Msg> rewritten = new ArrayList<>(messages);
                rewritten.set(mi, msg.withContent(content));
                return new ReasoningInput(rewritten, input.tools(), input.options());
            }
        }
        return input;
    }

    private static String extractText(ToolResultBlock result) {
        StringBuilder sb = new StringBuilder();
        if (result.getOutput() != null) for (ContentBlock block : result.getOutput())
            if (block instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
        return sb.toString();
    }

    private static int estimateTokens(int chars) {
        return chars <= 0 ? 0 : (int) Math.ceil(chars / 4.0);
    }

    /** Keep the system message and recent turns while preserving the original memory state. */
    private ReasoningInput compactOldMessages(ReasoningInput input) {
        if (input == null || input.messages() == null || input.messages().size() <= 7) return input;
        List<Msg> source = input.messages();
        int boundary = source.size() - 6;
        List<Msg> out = new ArrayList<>();
        out.add(source.get(0));
        out.add(Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("[早期对话已压缩，仅保留当前目标与最近工具结果]").build())
                .build());
        out.addAll(source.subList(Math.max(1, boundary), source.size()));
        return new ReasoningInput(out, input.tools(), input.options());
    }

    public static final class ContextBudgetExceededException extends RuntimeException {
        private final ContextSizeSnapshot snapshot;
        private final int budget;
        public ContextBudgetExceededException(ContextSizeSnapshot snapshot, int budget) {
            super("CONTEXT_BUDGET_EXCEEDED: estimated input tokens=" + snapshot.estimatedInputTokens()
                    + ", budget=" + budget);
            this.snapshot = snapshot;
            this.budget = budget;
        }
        public ContextSizeSnapshot snapshot() { return snapshot; }
        public int budget() { return budget; }
    }
}
