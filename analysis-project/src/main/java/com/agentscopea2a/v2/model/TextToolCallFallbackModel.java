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
package com.agentscopea2a.v2.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model 装饰器 —— 文本格式工具调用兜底。
 *
 * <p>某些模型通道不支持原生 function calling，会把工具调用当成纯文本输出
 * （分隔符 + {@code <function=NAME><parameter=KEY>value</parameter></function>}）。
 * 框架对这种格式没有解析器，结果是工具永不执行、ReAct 循环空转到 MAX_ITERATIONS，
 * 最后把这坨标记原样吐给用户。</p>
 *
 * <p>本装饰器在模型输出流上做有状态改写：普通文本增量原样放行（不影响流式输出）；
 * 一旦看到分隔符后跟 {@code <function} 标签，就扣住后续文本直到 {@code </function>}
 * 闭合，把整段解析成真正的 {@link ToolUseBlock} 再放出去，ReAct 循环即可正常执行工具。
 * 根因修复仍是换支持 function calling 的通道，这里只是兜底。</p>
 */
public class TextToolCallFallbackModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(TextToolCallFallbackModel.class);

    private final Model delegate;

    public TextToolCallFallbackModel(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // Flux.defer 保证每次订阅（每次真实模型调用）都有独立的状态机
        return Flux.defer(() -> {
            State state = new State();
            return delegate.stream(messages, tools, options)
                    .concatMap(chunk -> Flux.fromIterable(state.accept(chunk)))
                    .concatWith(Mono.fromCallable(state::flush)
                            .flatMapMany(Flux::fromIterable));
        });
    }

    /**
     * 流式状态机：TEXT 模式放行文本并探测标记；MARKUP 模式扣住文本直到函数块闭合。
     */
    private static final class State {

        private final StringBuilder pending = new StringBuilder();
        private boolean inMarkup = false;

        /** 处理一个流式 chunk，返回改写后要向下游发射的 0..n 个 chunk。 */
        List<ChatResponse> accept(ChatResponse chunk) {
            List<ContentBlock> outBlocks = new ArrayList<>();
            boolean sawText = false;
            for (ContentBlock block : chunk.getContent()) {
                if (block instanceof TextBlock tb) {
                    sawText = true;
                    outBlocks.addAll(acceptText(tb.getText()));
                } else {
                    outBlocks.add(block);
                }
            }
            if (!sawText) {
                return List.of(chunk);
            }
            if (outBlocks.isEmpty()) {
                // 文本全部被扣住（可能正跨在标记边界上），但 usage/finishReason 仍要透传
                return List.of(rebuild(chunk, List.of()));
            }
            return List.of(rebuild(chunk, outBlocks));
        }

        /** 流结束时冲刷未决文本：解析不出完整函数块的标记按普通文本放行。 */
        List<ChatResponse> flush() {
            if (pending.length() == 0) {
                return List.of();
            }
            String rest = pending.toString();
            pending.setLength(0);
            if (inMarkup && rest.indexOf('<') >= 0) {
                // 有 <function 开头但没有闭合 —— 解析失败，按原始文本放行
                //（进入 MARKUP 时已删掉开头的分隔符，这里补回去保证原文完整）
                rest = String.valueOf(TextToolCallParser.MARKER) + rest;
                log.warn("TextToolCall fallback: unclosed function markup at stream end, "
                        + "passing through as text ({} chars)", rest.length());
            }
            return List.of(textResponse(rest));
        }

        private List<ContentBlock> acceptText(String delta) {
            pending.append(delta);
            List<ContentBlock> out = new ArrayList<>();
            while (true) {
                if (!inMarkup) {
                    int mi = pending.indexOf(String.valueOf(TextToolCallParser.MARKER));
                    if (mi < 0) {
                        drainAsText(out);
                        break;
                    }
                    if (mi > 0) {
                        emitText(out, pending.substring(0, mi));
                        pending.delete(0, mi);
                    }
                    // pending 现以标记开头；判断后面跟的是标记文本还是普通文本
                    int decision = TextToolCallParser.classifyAfterMarker(pending.substring(1));
                    if (decision == 0) {
                        break; // 跨在标记边界上，等下一个增量再定
                    }
                    if (decision == 1) {
                        pending.deleteCharAt(0); // 丢弃分隔符本身
                        inMarkup = true;
                        continue;
                    }
                    // 误报：标记本身是普通文本
                    emitText(out, String.valueOf(TextToolCallParser.MARKER));
                    pending.deleteCharAt(0);
                } else {
                    String p = pending.toString();
                    int ws = 0;
                    while (ws < p.length() && Character.isWhitespace(p.charAt(ws))) {
                        ws++;
                    }
                    String rest = p.substring(ws);
                    if (rest.isEmpty()) {
                        break; // 全是空白：可能还有连续块，等下一个增量
                    }
                    int r = TextToolCallParser.matchPrefix(rest, "<function=");
                    if (r == 0) {
                        break; // 标签跨增量，等
                    }
                    if (r < 0) {
                        inMarkup = false; // 连续块结束，剩余按文本处理
                        continue;
                    }
                    int fEnd = TextToolCallParser.findFunctionBlockEnd(p, ws);
                    if (fEnd < 0) {
                        break; // 等待闭合标签
                    }
                    String segment = p.substring(ws, fEnd);
                    List<TextToolCallParser.ParsedToolCall> calls =
                            TextToolCallParser.parseFunctionBlocks(segment);
                    if (calls.isEmpty()) {
                        emitText(out, segment);
                    } else {
                        for (TextToolCallParser.ParsedToolCall call : calls) {
                            out.add(new ToolUseBlock(newToolCallId(), call.name(), call.input()));
                        }
                    }
                    pending.delete(0, fEnd);
                    // 保持 MARKUP，循环检查是否还有连续的 <function> 块（一次多个工具调用）
                }
            }
            return out;
        }

        private void drainAsText(List<ContentBlock> out) {
            if (pending.length() > 0) {
                emitText(out, pending.toString());
                pending.setLength(0);
            }
        }

        private static void emitText(List<ContentBlock> out, String text) {
            if (!text.isEmpty()) {
                out.add(TextBlock.builder().text(text).build());
            }
        }
    }

    private static String newToolCallId() {
        return "texttool-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ChatResponse rebuild(ChatResponse src, List<ContentBlock> content) {
        return new ChatResponse(src.getId(), content, src.getUsage(), src.getMetadata(), src.getFinishReason());
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse("texttool-flush",
                List.of(TextBlock.builder().text(text).build()), null, null, null);
    }
}
