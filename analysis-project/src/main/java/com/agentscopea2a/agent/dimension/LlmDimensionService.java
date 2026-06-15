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

import reactor.core.publisher.Mono;

/**
 * LLM 服务接口，供 DimensionStateManager 调用。
 *
 * <p>使用者需根据实际 LLM 接入方式实现此接口。
 */
public interface LlmDimensionService {

    /**
     * 同步调用 LLM。
     *
     * @param prompt 完整的 prompt
     * @return LLM 的文本响应
     */
    String call(String prompt);

    /**
     * 异步调用 LLM。
     *
     * @param prompt 完整的 prompt
     * @return LLM 文本响应的 Mono
     */
    Mono<String> callAsync(String prompt);
}
