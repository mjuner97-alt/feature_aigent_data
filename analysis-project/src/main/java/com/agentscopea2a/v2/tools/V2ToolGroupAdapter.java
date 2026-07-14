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
package com.agentscopea2a.v2.tools;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolGroupScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that creates a v2 {@link Toolkit} with tool groups, replacing the v1
 * {@link ToolRoutersIndex}'s reflection-based {@code router_tool} dispatch pattern.
 *
 * <p>Tool groups provide:
 * <ul>
 *   <li>Native tool registration (no JSON-based {@code router_tool} indirection)</li>
 *   <li>Dynamic activation/deactivation via the {@code reset_equipped_tools} meta-tool</li>
 *   <li>Per-group scope (META = agent-managed, EXTERNAL = developer-managed)</li>
 * </ul>
 *
 * <p>The adapter registers tools from existing {@code @Tool}-annotated beans into named groups,
 * and enables the meta-tool for LLM-driven group switching. The resulting {@link Toolkit}
 * is wired into {@link com.agentscopea2a.v2.runner.HarnessA2aRunnerV2} via
 * {@code HarnessAgent.Builder.toolkit()}.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2ToolConfig}.
 */
public class V2ToolGroupAdapter {

    private static final Logger log = LoggerFactory.getLogger(V2ToolGroupAdapter.class);

    private final Toolkit toolkit;

    public V2ToolGroupAdapter(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * Returns the configured toolkit with all tool groups and the meta-tool registered.
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Builder for constructing a {@link V2ToolGroupAdapter} with registered tool groups.
     *
     * <p>Usage:
     * <pre>{@code
     * V2ToolGroupAdapter adapter = V2ToolGroupAdapter.builder()
     *     .tool(agentTools, "quality_tools")
     *     .tool(dataPrimitivesTool, "data_primitives")
     *     .enableMetaTool()
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private final Toolkit toolkit = new Toolkit();

        /**
         * Register a tool object (with {@code @Tool}-annotated methods) into a named group.
         *
         * @param toolObject the object containing {@code @Tool} methods
         * @param groupName the tool group name (created if not exists)
         * @return this builder for chaining
         */
        public Builder tool(Object toolObject, String groupName) {
            ensureGroup(groupName);
            toolkit.registration()
                    .tool(toolObject)
                    .group(groupName)
                    .apply();
            log.info("V2ToolGroupAdapter: registered {} methods into group '{}'",
                    toolObject.getClass().getSimpleName(), groupName);
            return this;
        }

        /**
         * Register a tool object without a group (always available, not subject to group activation).
         *
         * @param toolObject the object containing {@code @Tool} methods
         * @return this builder for chaining
         */
        public Builder tool(Object toolObject) {
            toolkit.registerTool(toolObject);
            log.info("V2ToolGroupAdapter: registered {} methods (ungrouped)",
                    toolObject.getClass().getSimpleName());
            return this;
        }

        /**
         * Create a tool group with META scope (agent-managed via {@code reset_equipped_tools}).
         *
         * @param groupName the group name
         * @param description human-readable description shown to the LLM
         * @param active initial activation state
         * @return this builder for chaining
         */
        public Builder createGroup(String groupName, String description, boolean active) {
            toolkit.createToolGroup(groupName, description, active, ToolGroupScope.META);
            log.info("V2ToolGroupAdapter: created META group '{}' (active={})", groupName, active);
            return this;
        }

        /**
         * Create a tool group with EXTERNAL scope (developer-managed, invisible to meta-tool).
         *
         * @param groupName the group name
         * @param description human-readable description
         * @param active initial activation state
         * @return this builder for chaining
         */
        public Builder createExternalGroup(String groupName, String description, boolean active) {
            toolkit.createToolGroup(groupName, description, active, ToolGroupScope.EXTERNAL);
            log.info("V2ToolGroupAdapter: created EXTERNAL group '{}' (active={})", groupName, active);
            return this;
        }

        /**
         * Register the {@code reset_equipped_tools} meta-tool.
         * This allows the LLM to dynamically activate/deactivate META-scoped tool groups.
         *
         * @return this builder for chaining
         */
        public Builder enableMetaTool() {
            toolkit.registerMetaTool();
            log.info("V2ToolGroupAdapter: registered meta-tool (reset_equipped_tools)");
            return this;
        }

        /**
         * Build the adapter with all registered tools and groups.
         *
         * @return the configured {@link V2ToolGroupAdapter}
         */
        public V2ToolGroupAdapter build() {
            log.info("V2ToolGroupAdapter: built toolkit with {} tools, {} groups",
                    toolkit.getToolNames().size(), toolkit.getActiveGroups().size());
            return new V2ToolGroupAdapter(toolkit);
        }

        /**
         * Ensure a tool group exists before registering tools into it.
         * Creates it as META-scoped with default activation if it doesn't exist.
         */
        private void ensureGroup(String groupName) {
            if (toolkit.getToolGroup(groupName) == null) {
                toolkit.createToolGroup(groupName, "Tool group: " + groupName, true, ToolGroupScope.META);
            }
        }
    }

    /**
     * Create a new builder for configuring tool groups.
     */
    public static Builder builder() {
        return new Builder();
    }
}