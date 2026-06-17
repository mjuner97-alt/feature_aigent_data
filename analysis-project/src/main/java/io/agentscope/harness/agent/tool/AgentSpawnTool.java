/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local override of upstream {@code io.agentscope.harness.agent.tool.AgentSpawnTool}
 * (从 {@code agentscope-harness-1.1.0-RC1.jar} 反编译而来) —— 仅用于把派单默认超时从 30s
 * 提到 600s,并把上限从 600s 提到 3600s,绕过远端 MySQL 冷连接 / 慢 SQL 在 30s 内炸出
 * {@code Timeout on blocking read for 30000000000 NANOSECONDS} 的硬限。
 *
 * <p>选择源码 shadow 而非 PreActingEvent Hook 的理由:上游字节码里
 * {@code DEFAULT_TIMEOUT_SECONDS=30} 与 {@code resolveTimeoutMs} 内的 {@code Math.min(_, 600)}
 * 两处都是 javac 内联的字面量 / 静态常量,反射或运行时配置改不动。Hook 又只能挂在 supervisor
 * 这一层,子 agent → 子 agent 嵌套派单(MAX_SPAWN_DEPTH=3 允许)走不到。Shadow 是项目里
 * {@code DockerSandboxClient} 验证过的模式 ——
 * {@code target/classes/} 在 Spring Boot LaunchedClassLoader 顺序里压过
 * {@code BOOT-INF/lib/agentscope-harness-1.1.0-RC1.jar}。
 *
 * <p><b>vs upstream 的精确 diff(共 4 处)</b>:
 *
 * <ol>
 *   <li>{@code DEFAULT_TIMEOUT_SECONDS}: 30 → 600
 *   <li>{@code MAX_TIMEOUT_SECONDS}: 600 → 3600
 *   <li>{@code resolveTimeoutMs} 内的 cap: {@code Math.min(timeoutSeconds, 600)} →
 *       {@code Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS)} (改用常量,与上面一致)
 *   <li>{@code agent_spawn}/{@code agent_send} 工具描述里 "Default: 30. Max: 600." →
 *       "Default: 600. Max: 3600." (LLM 看到的派单工具签名同步更新)
 * </ol>
 *
 * <p>升级 {@code agentscope-harness} 版本时,务必 diff 此文件与新版 jar 内的同名类。
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    /** SHADOW: 上游 30s 太短,远端 MySQL 冷连接 + 慢 SQL 必炸。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** SHADOW: 上游 600s 上限太死,显式给 1200/1800 会被截。 */
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private static final int MAX_SPAWN_DEPTH = 3;

    private static final String BG_RESULT_TEMPLATE =
            "status: accepted\ntask_id: %s\nUse task_output(task_id='%s') to retrieve the result,"
                    + " task_cancel(task_id='%s') to cancel, or task_list() to see all tasks.";

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;
    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();

    public AgentSpawnTool(
            DefaultAgentManager agentManager, TaskRepository taskRepository, int parentSpawnDepth) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
    }

    @Tool(
            name = "agent_spawn",
            description =
                    "Spawn an isolated subagent for delegated or background work. Every response"
                        + " starts with three lines: agent_key (pass this verbatim to agent_send"
                        + " as agent_key), agent_id (the subagent type name), and session_id"
                        + " (internal; do not use as agent_key). Sync mode returns the reply below"
                        + " that; async (timeout_seconds=0) adds task_id for task_output —"
                        + " task_id is NOT agent_key.")
    public String agentSpawn(
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "Task or prompt to send to the spawned agent",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "Optional human-readable label for referencing via agent_send",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    "Max seconds to wait for the task result. 0=fire-and-forget,"
                                            + " returns task_id. Default: 600. Max: 3600.",
                            required = false)
                    Integer timeoutSeconds) {
        int nextDepth = this.parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            return "Error: Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")";
        }
        if (!this.agentManager.hasAgent(agentId)) {
            return "Error: Unknown agent_id: " + agentId;
        }
        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        if (canonLabel != null && this.labelToKey.containsKey(canonLabel.toLowerCase())) {
            return "Error: Label already in use: " + canonLabel;
        }
        Agent agent = this.agentManager.createAgent(agentId);
        String key = "agent:" + agentId + ":" + UUID.randomUUID();
        String sessionId = "sub-" + UUID.randomUUID();
        SpawnedAgent spawned =
                new SpawnedAgent(key, agentId, sessionId, canonLabel, agent, nextDepth);
        this.agentsByKey.put(key, spawned);
        if (canonLabel != null) {
            this.labelToKey.put(canonLabel.toLowerCase(), key);
        }
        String spawnInfo = formatSpawnInfo(key, agentId, sessionId);

        boolean hasTask = task != null && !task.isBlank();
        if (!hasTask) {
            return spawnInfo + "\nstatus: accepted";
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        if (timeoutMs == 0L) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            this.taskRepository.putTask(
                    taskId,
                    agentId,
                    () -> {
                        try {
                            Msg reply =
                                    this.agentManager
                                            .invokeAgent(agent, sessionId, capturedTask)
                                            .block();
                            return reply != null ? reply.getTextContent() : "";
                        } catch (RuntimeException e) {
                            return "Error: "
                                    + (e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName());
                        }
                    });
            return spawnInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId);
        }
        try {
            Msg reply =
                    this.agentManager
                            .invokeAgent(agent, sessionId, task.trim())
                            .block(Duration.ofMillis(timeoutMs));
            String text = reply != null ? reply.getTextContent() : "";
            return spawnInfo + "\nstatus: ok\nreply:\n" + text;
        } catch (RuntimeException e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("agent_spawn execute failed: agentId={}", agentId, e);
            return spawnInfo + "\nstatus: error\nerror: " + err;
        }
    }

    @Tool(
            name = "agent_send",
            description =
                    "Send a message to an existing subagent. Use the exact string from the"
                        + " agent_key line of agent_spawn output (starts with agent:), or the label"
                        + " you set at spawn. Do not pass agent_id, session_id, or task_id here."
                        + " timeout_seconds=0 returns task_id for task_output.")
    public String agentSend(
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "Exact value from agent_spawn's first line after 'agent_key: '"
                                        + " (format agent:<type>:<uuid>). Not agent_id, session_id,"
                                        + " or task_id. Mutually exclusive with label.",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "Agent label assigned at spawn time. Mutually exclusive with"
                                            + " agent_key.",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "Message to send to the subagent")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    "Max seconds to wait for a reply. 0=fire-and-forget, returns"
                                            + " task_id. Default: 600. Max: 3600.",
                            required = false)
                    Integer timeoutSeconds) {
        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return "Error: Provide either agent_key or label, not both.";
        }
        if (!hasKey && !hasLabel) {
            return "Error: Either agent_key or label is required.";
        }
        if (message == null || message.isBlank()) {
            return "Error: message is required";
        }
        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key = this.labelToKey.get(label.trim().toLowerCase());
            if (key == null) {
                return "Error: Unknown label: " + label.trim();
            }
        }
        SpawnedAgent spawned = this.agentsByKey.get(key);
        if (spawned == null) {
            return "Error: Unknown agent_key: " + key;
        }
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        if (timeoutMs == 0L) {
            String taskId = "task_" + UUID.randomUUID();
            this.taskRepository.putTask(
                    taskId,
                    spawned.agentId(),
                    () -> {
                        try {
                            Msg reply =
                                    this.agentManager
                                            .invokeAgent(spawned.agent(), spawned.sessionId(), message)
                                            .block();
                            return reply != null ? reply.getTextContent() : "";
                        } catch (RuntimeException e) {
                            return "Error: "
                                    + (e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName());
                        }
                    });
            return String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId);
        }
        try {
            Msg reply =
                    this.agentManager
                            .invokeAgent(spawned.agent(), spawned.sessionId(), message.trim())
                            .block(Duration.ofMillis(timeoutMs));
            String text = reply != null ? reply.getTextContent() : "";
            return "agent_key: " + key + "\nstatus: ok\nreply:\n" + text;
        } catch (RuntimeException e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("agent_send failed: key={}", key, e);
            return "Error: " + err;
        }
    }

    @Tool(name = "agent_list", description = "List active subagents spawned by this agent.")
    public String agentList() {
        if (this.agentsByKey.isEmpty()) {
            return "No active subagents.";
        }
        StringBuilder sb =
                new StringBuilder("Active subagents (").append(this.agentsByKey.size()).append("):\n");
        for (SpawnedAgent a : this.agentsByKey.values()) {
            sb.append("- agent_key: ").append(a.key()).append("\n");
            sb.append("  agent_id: ").append(a.agentId()).append("\n");
            if (a.label() != null) {
                sb.append("  label: ").append(a.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(a.depth()).append("\n");
        }
        return sb.toString().trim();
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1000L;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1000L;
    }

    private static String formatSpawnInfo(String key, String agentId, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        return sb.toString();
    }

    /**
     * 嵌套 record,与 upstream 同名 ({@code AgentSpawnTool$SpawnedAgent})。javac 会生成同样的
     * 类名,签名 {@code (String key, String agentId, String sessionId, String label, Agent agent,
     * int depth)} 与 upstream 字节码一致。
     */
    record SpawnedAgent(
            String key,
            String agentId,
            String sessionId,
            String label,
            Agent agent,
            int depth) {}
}
