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
 * Tool execution failures - router not found, parameter deserialization failed, tool threw.
 *
 * <p>Carries {@code toolId} so the framework / caller can route the error back to the LLM
 * with a clear "which tool" message, not just "something blew up".
 */
public class ToolExecutionException extends V2RuntimeException {

    private final String toolId;

    public ToolExecutionException(String toolId, String message) {
        super(message);
        this.toolId = toolId;
    }

    public ToolExecutionException(String toolId, String message, Throwable cause) {
        super(message, cause);
        this.toolId = toolId;
    }

    public String getToolId() {
        return toolId;
    }
}
