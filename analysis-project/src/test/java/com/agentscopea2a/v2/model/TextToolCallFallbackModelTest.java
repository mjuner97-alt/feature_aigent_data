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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本格式工具调用兜底解析测试：标记 + function 标记文本（含流式拆分边界）→ 真实 ToolUseBlock。
 */
class TextToolCallFallbackModelTest {

    private static final String MARKER = String.valueOf(TextToolCallParser.MARKER);

    /** 构造与线上 bug 相同的标记文本（避免在源码里写字面分隔符）。 */
    private static String markup(String tool, String... params) {
        StringBuilder sb = new StringBuilder(MARKER).append('\n');
        sb.append("<function=").append(tool).append(">\n");
        for (int i = 0; i + 1 < params.length; i += 2) {
            sb.append("<parameter=").append(params[i]).append(">\n")
                    .append(params[i + 1]).append("\n</parameter>\n");
        }
        sb.append("</function>");
        return sb.toString();
    }

    /** 按 deltaSize 把文本切成多个流式增量，模拟 SSE 分片（标记可能被劈开）。 */
    private static List<String> split(String text, int deltaSize) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += deltaSize) {
            parts.add(text.substring(i, Math.min(text.length(), i + deltaSize)));
        }
        return parts;
    }

    private static Model modelEmitting(String fullText, int deltaSize) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                List<ChatResponse> chunks = new ArrayList<>();
                for (String part : split(fullText, deltaSize)) {
                    chunks.add(new ChatResponse("call-1",
                            List.of(TextBlock.builder().text(part).build()), null, null, null));
                }
                chunks.add(new ChatResponse("call-1", List.of(), null, null, "stop"));
                return Flux.fromIterable(chunks);
            }

            @Override
            public String getModelName() {
                return "test-model";
            }
        };
    }

    private static List<ChatResponse> collect(Model model) {
        return model.stream(List.of(), List.of(), null).collectList().block();
    }

    private static String joinText(List<ChatResponse> responses) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse r : responses) {
            for (ContentBlock b : r.getContent()) {
                if (b instanceof TextBlock tb) {
                    sb.append(tb.getText());
                }
            }
        }
        return sb.toString();
    }

    private static List<ToolUseBlock> toolCalls(List<ChatResponse> responses) {
        List<ToolUseBlock> calls = new ArrayList<>();
        for (ChatResponse r : responses) {
            for (ContentBlock b : r.getContent()) {
                if (b instanceof ToolUseBlock tub) {
                    calls.add(tub);
                }
            }
        }
        return calls;
    }

    // ==================== 解析为工具调用 ====================

    @Test
    void convertsFullMarkupToToolUseBlock() {
        String text = "让我查一下。\n" + markup("toolMetaInfo", "toolId", "getPersonByDept");
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 3)));

        List<ToolUseBlock> calls = toolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("toolMetaInfo", calls.get(0).getName());
        assertEquals("getPersonByDept", calls.get(0).getInput().get("toolId"));
        assertEquals("让我查一下。\n", joinText(out));
    }

    @Test
    void convertsMarkupAcrossSplitDeltas() {
        // delta=2 保证标记和标签都被劈碎
        String text = markup("router_tool", "action", "query", "params", "{\"k\":1}");
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 2)));

        List<ToolUseBlock> calls = toolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("router_tool", calls.get(0).getName());
        assertEquals("query", calls.get(0).getInput().get("action"));
        assertEquals("{\"k\":1}", calls.get(0).getInput().get("params"));
        assertEquals("", joinText(out));
    }

    @Test
    void convertsMultipleConsecutiveFunctionBlocks() {
        String text = MARKER + "\n"
                + "<function=tool_a>\n<parameter=x>1</parameter>\n</function>\n"
                + "<function=tool_b>\n<parameter=y>2</parameter>\n</function>";
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 7)));

        List<ToolUseBlock> calls = toolCalls(out);
        assertEquals(2, calls.size());
        assertEquals("tool_a", calls.get(0).getName());
        assertEquals("tool_b", calls.get(1).getName());
        assertEquals("1", calls.get(0).getInput().get("x"));
        assertEquals("2", calls.get(1).getInput().get("y"));
    }

    // ==================== 普通文本不受影响 ====================

    @Test
    void passesPlainTextThroughUnchanged() {
        String text = "普通回答，没有任何标记。包括 <function=foo> 出现在正文里但没有分隔符前缀，不应被解析。";
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 5)));
        assertEquals(text, joinText(out));
        assertEquals(0, toolCalls(out).size());
    }

    @Test
    void markerAloneWithoutFunctionTagIsText() {
        String text = "符号 " + MARKER + " 出现在正文里，后面没有函数标签，按文本放行。";
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 4)));
        assertEquals(text, joinText(out));
        assertEquals(0, toolCalls(out).size());
    }

    @Test
    void unclosedMarkupAtStreamEndFallsBackToText() {
        String text = MARKER + "\n<function=tool_a>\n<parameter=x>1</parameter>"; // 没有 </function>
        List<ChatResponse> out = collect(new TextToolCallFallbackModel(modelEmitting(text, 3)));
        assertEquals(text, joinText(out));
        assertEquals(0, toolCalls(out).size());
    }

    @Test
    void getModelNameDelegates() {
        assertEquals("test-model", new TextToolCallFallbackModel(modelEmitting("hi", 2)).getModelName());
    }
}
