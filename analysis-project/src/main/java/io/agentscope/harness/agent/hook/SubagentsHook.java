/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Local override of upstream {@code SubagentsHook} from {@code agentscope-harness-1.1.0-RC1.jar}.
 *
 * <p>Kept in sync with the local {@link AgentSpawnTool} shadow: the runtime default/cap there is
 * 600s/3600s, while upstream's injected system prompt still says default=30/max=600. The only
 * intentional diff from upstream is the timeout wording in {@link #SUBAGENT_SECTION_TEMPLATE}.
 * Diff against upstream when bumping the harness version.
 */
public class SubagentsHook implements Hook {

    private static final String SUBAGENT_SECTION_TEMPLATE =
            "\n## Subagents\n\n"
                    + "You have access to subagent tools for spawning and coordinating isolated subagents.\n"
                    + "Subagents are ephemeral — they live only for the duration of the task and return a single result.\n\n"
                    + "### Agent Tools\n\n"
                    + "**`%s`** — Spawn an isolated subagent\n"
                    + "- `agent_id` (required): which subagent to instantiate\n"
                    + "- `task` (optional): initial prompt; omit to create a persistent session\n"
                    + "- `label` (optional): human-readable name for referencing via send\n"
                    + "- `timeout_seconds`: wait time; 0=fire-and-forget (returns task_id), default=600, max=3600\n"
                    + "- Response always includes `agent_key:` (opaque handle) — save it for follow-up sends\n\n"
                    + "**`%s`** — Send a follow-up message to an existing subagent\n"
                    + "- `agent_key`: copy the **full value** after `agent_key:` from spawn output (starts with `agent:`). This is NOT `agent_id`, NOT `session_id`, and NOT `task_id`\n"
                    + "- Or use `label` if you set one at spawn (mutually exclusive with agent_key)\n"
                    + "- `message` (required): content to send\n"
                    + "- `timeout_seconds`: 0=fire-and-forget, >0=wait for reply (default: 600, max: 3600)\n\n"
                    + "**`%s`** — List active subagents\n\n"
                    + "### Task Tools (for async/background operations)\n\n"
                    + "**`task_output`** — Retrieve the result of a background task by task_id. Supports blocking wait (default) or non-blocking peek (block=false).\n\n"
                    + "**`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.\n\n"
                    + "**`task_list`** — List all background tasks with current statuses. Optionally filter by status (running, completed, failed, cancelled).\n\n"
                    + "### Available agent ids\n%s\n\n"
                    + "### When to use subagents\n"
                    + "- When a task is complex and multi-step, and can be fully delegated in isolation\n"
                    + "- When a task is independent of other tasks and can run in parallel\n"
                    + "- When a task requires focused reasoning or heavy context usage that would bloat the main thread\n"
                    + "- When sandboxing improves reliability (e.g. code analysis, structured searches, data formatting)\n"
                    + "- When you only care about the output, not the intermediate steps (e.g. research → synthesized report)\n\n"
                    + "### When NOT to use subagents\n"
                    + "- If the task is trivial (a few tool calls or simple lookup)\n"
                    + "- If you need to see intermediate reasoning or steps after completion\n"
                    + "- If delegating does not reduce token usage, complexity, or context switching\n"
                    + "- If splitting would add latency without benefit\n\n"
                    + "### Subagent lifecycle\n"
                    + "1. **Spawn** → Provide clear role, instructions, and expected output format\n"
                    + "2. **Run** → The subagent completes the task autonomously\n"
                    + "3. **Return** → The subagent provides a single structured result\n"
                    + "4. **Reconcile** → Incorporate or synthesize the result into the main thread\n\n"
                    + "### Usage patterns\n"
                    + "- **Parallel execution**: Launch multiple subagents concurrently with timeout_seconds=0 when tasks are independent, then collect results with task_output\n"
                    + "- **Sync delegation**: Use default timeout for simple one-shot delegation\n"
                    + "- **Persistent session**: Spawn without a task, then use send for multi-turn interaction\n"
                    + "- **Cancel stale work**: Use task_cancel to stop background tasks that are no longer needed\n"
                    + "- Subagent results are NOT visible to the user — always summarize them in your response\n";

    private final List<SubagentEntry> entries;
    private final Object subagentTool;
    private final TaskTool taskTool;
    private final boolean isSessionMode;

    public SubagentsHook(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this.entries = List.copyOf(entries);
        this.isSessionMode = false;
        Map<String, SubagentFactory> factories = buildFactories(entries);
        DefaultAgentManager dam = new DefaultAgentManager(factories, workspaceManager);
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.subagentTool = new AgentSpawnTool(dam, repo, 0);
        this.taskTool = new TaskTool(repo);
    }

    public SubagentsHook(
            List<SubagentEntry> entries, Object externalSubagentTool, TaskRepository taskRepository) {
        this.entries = List.copyOf(entries);
        this.isSessionMode = true;
        this.subagentTool = externalSubagentTool;
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.taskTool = new TaskTool(repo);
    }

    public SubagentsHook(List<SubagentEntry> entries) {
        this(entries, null, (WorkspaceManager) null);
    }

    public List<Object> tools() {
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent preReasoning) {
            injectSubagentPrompt(preReasoning);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 80;
    }

    private void injectSubagentPrompt(PreReasoningEvent event) {
        if (entries.isEmpty()) {
            return;
        }
        String agentList =
                entries.stream()
                        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                        .collect(Collectors.joining("\n"));
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        String section = String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, agentList);
        event.appendSystemContent(section);
    }

    private static Map<String, SubagentFactory> buildFactories(List<SubagentEntry> entries) {
        HashMap<String, SubagentFactory> factories = new HashMap<>();
        for (SubagentEntry entry : entries) {
            factories.put(entry.name(), entry.factory());
        }
        return factories;
    }

    public record SubagentEntry(String name, String description, SubagentFactory factory) {}
}
