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
 * 管理者智能体桥接工具 —— 综合调用 Doc / Rule 等管理类能力。
 */
public class ManagerAgentTool {

    @Tool(
            name = "manager_agent",
            description = "调用管理者智能体处理管理类问题(规则查询、制度解读、审批指引等)。")
    public ToolResultBlock invoke(
            @ToolParam(name = "question", description = "用户提的管理类问题") String question) {
        // TODO: 转发到 ManagerAgent 子智能体
        return ToolResultBlock.text("ManagerAgent 占位: q=" + question);
    }
}
