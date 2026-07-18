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
 * Sandbox container unavailable - timed out on acquire, docker daemon down, etc.
 *
 * <p>Distinguished from {@link MemoryPersistenceException} because the caller (typically
 * {@code PythonExecTool} or {@code SandboxLifecycleMiddleware}) can fall back to a degraded
 * "no code execution" reply rather than aborting the whole chat request.
 */
public class SandboxUnavailableException extends V2RuntimeException {

    public SandboxUnavailableException(String message) {
        super(message);
    }

    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
