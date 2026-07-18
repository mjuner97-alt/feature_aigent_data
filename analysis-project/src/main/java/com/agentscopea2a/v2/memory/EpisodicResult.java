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
package com.agentscopea2a.v2.memory;

import io.agentscope.core.state.State;
import java.time.LocalDateTime;

/**
 * A single result from episodic memory search.
 *
 * <p>v2 relocation: moved from {@code io.agentscope.core.memory} (shadow override)
 * to {@code com.agentscopea2a.v2.memory} (business record).
 *
 * @param sessionId The session where this result was found
 * @param snippet The matching text snippet
 * @param relevance Relevance score (higher is more relevant)
 * @param timestamp When the message was recorded
 */
public record EpisodicResult(
        String sessionId, String snippet, double relevance, LocalDateTime timestamp)
        implements State {}
