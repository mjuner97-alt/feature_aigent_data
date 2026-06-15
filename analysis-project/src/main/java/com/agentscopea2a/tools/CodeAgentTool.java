/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 调用代码生成 / 代码执行子智能体的桥接工具。
 *
 * <p>把请求转发给底层 code-interpreter 子智能体,获得代码执行结果。
 */
public class CodeAgentTool {

    @Tool(
            name = "code_agent",
            description = "调用代码生成/执行子智能体。输入自然语言任务,返回代码及其运行结果。")
    public ToolResultBlock invokeCodeAgent(
            @ToolParam(name = "task", description = "需要代码完成的任务描述") String task,
            @ToolParam(name = "language", description = "目标编程语言,如 python / java / sql", required = false)
                    String language) {
        // TODO: 转发给 code-interpreter 子智能体
        return ToolResultBlock.text("CodeAgent 占位: task=" + task + ", lang=" + language);
    }
}
