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
 * Base class for all v2 runtime exceptions.
 *
 * <p>Replaces ad-hoc {@code new RuntimeException(...)} / {@code new IllegalStateException(...)}
 * at recovery boundaries so callers can catch by intent (e.g. {@code catch (MemoryPersistenceException)}
 * = "the DB write failed, abort the request") rather than by message string.
 *
 * <p>Convention (see optimization-analysis.md P1-4):
 * <ul>
 *   <li><b>Recoverable</b> failures (PR4 best-effort accounting, optional hooks, cache lookups):
 *       log at debug/warn and swallow. Do <em>not</em> throw.</li>
 *   <li><b>Should-throw</b> failures (memory persistence, distillation): wrap in the appropriate
 *       subclass and rethrow. The caller decides whether to surface to the user or degrade.</li>
 * </ul>
 */
public class V2RuntimeException extends RuntimeException {

    public V2RuntimeException(String message) {
        super(message);
    }

    public V2RuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
