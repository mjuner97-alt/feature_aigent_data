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
 * 固定周期提交(版本节奏)子智能体桥接工具。
 *
 * <p>查询/分析项目在固定提测/上线周期内的执行情况。
 */
public class FixedCycleCommitAgentTool {

    @Tool(
            name = "fixed_cycle_commit_agent",
            description = "调用固定周期提交分析子智能体,分析项目按固定周期提测/上线的达成情况。")
    public ToolResultBlock invoke(
            @ToolParam(name = "department", description = "部门") String department,
            @ToolParam(name = "cycle", description = "周期描述,如 'Q1' / '2026-04'") String cycle) {
        // TODO: 调用底层固定周期分析 agent
        return ToolResultBlock.text(
                "FixedCycleCommit 占位: dept=" + department + ", cycle=" + cycle);
    }
}
