/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.agent.dimension;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public class OpenAILlmDimensionService implements LlmDimensionService {

    private final Model model;
    private final GenerateOptions overrideOptions;

    /**
     * 使用已有 Model 实例创建。
     *
     * <p>Model 实例中已包含 apiKey、baseUrl、modelName 等配置，
     * 每次调用时将使用 Model 的默认配置。
     *
     * @param model 已配置好的 Model 实例
     */
    public OpenAILlmDimensionService(Model model) {
        this(model, null);
    }

    /**
     * 使用已有 Model 实例 + 覆盖选项创建。
     *
     * <p>覆盖选项中的 apiKey、baseUrl、modelName 等将优先于 Model 自身的配置。
     * 适用于需要用不同模型/密钥做维度分析的场景。
     *
     * @param model           已配置好的 Model 实例
     * @param overrideOptions 覆盖选项（可为 null）
     */
    public OpenAILlmDimensionService(Model model, GenerateOptions overrideOptions) {
        this.model = model;
        this.overrideOptions = overrideOptions;
    }

    @Override
    public String call(String prompt) {
        return callAsync(prompt).block();
    }

    @Override
    public Mono<String> callAsync(String prompt) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(prompt).build();

        return Mono.fromCallable(
                () -> {
                    Flux<ChatResponse> flux = model.stream(List.of(userMsg), null, overrideOptions);

                    // 收集完整响应（非流式场景，collect 全部 chunk）
                    StringBuilder sb = new StringBuilder();
                    flux.toStream()
                            .forEach(
                                    response -> {
                                        if (response.getContent() != null) {
                                            for (ContentBlock block : response.getContent()) {
                                                if (block instanceof TextBlock textBlock) {
                                                    sb.append(textBlock.getText());
                                                }
                                            }
                                        }
                                    });

                    return sb.toString();
                });
    }
}
