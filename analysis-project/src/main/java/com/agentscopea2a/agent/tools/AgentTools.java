/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 通用 Agent 工具集 —— 提供其它 Tool 类未覆盖的杂项能力。
 *
 * <p>本类作为新工具入口,具体业务方法在此扩展。
 */
public class AgentTools {

    @Tool(
            name = "agent_tools_ping",
            description = "通用工具占位方法,用于验证工具注册链路是否就绪。返回 'pong'。")
    public ToolResultBlock ping(
            @ToolParam(name = "echo", description = "回显内容,可选", required = false) String echo) {
        // TODO: 替换为真正的通用工具实现
        String content = echo == null ? "pong" : "pong: " + echo;
        return ToolResultBlock.text(content);
    }
}
