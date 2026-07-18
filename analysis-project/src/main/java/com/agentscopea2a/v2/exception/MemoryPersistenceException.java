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
package com.agentscopea2a.v2.exception;

/**
 * Episodic memory / ledger persistence failures.
 *
 * <p>Thrown by {@code MySqlEpisodicMemory.recordSession} / {@code recordSessionWithToolContext}
 * when the SQL batch can't be committed. Callers (typically {@code V2ChatStreamServiceImpl}
 * cleanup path) should let this propagate so the request fails loudly rather than silently
 * dropping the conversation history.
 *
 * <p>Distinct from generic {@link java.sql.SQLException}: this one means "the user-visible
 * request is incomplete because we couldn't persist the dialogue", not "a SQL op happened
 * to fail".
 */
public class MemoryPersistenceException extends V2RuntimeException {

    public MemoryPersistenceException(String message) {
        super(message);
    }

    public MemoryPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
