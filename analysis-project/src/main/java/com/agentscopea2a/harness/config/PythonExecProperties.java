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
package com.agentscopea2a.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tool timeout knobs for {@code python_exec}. See {@code docs/code-interpreter-optimization.md}
 * §P1-D.
 *
 * <p>Default 60s matches the legacy hard-coded value; max 300s prevents a runaway LLM from
 * blocking the outer {@code agent_spawn}'s 300s budget entirely. {@code PythonExecTool} clamps
 * the LLM-passed {@code timeoutSeconds} into {@code [1, max]} and falls back to {@code default}
 * when the LLM doesn't provide one.
 */
@Component
@ConfigurationProperties(prefix = "harness.a2a.python-exec")
public class PythonExecProperties {

    private int defaultTimeoutSeconds = 60;
    private int maxTimeoutSeconds = 300;

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }
}
