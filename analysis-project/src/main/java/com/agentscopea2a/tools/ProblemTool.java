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
 * 问题查询工具 —— 查询缺陷/工单/Issue 等记录。
 */
public class ProblemTool {

    @Tool(
            name = "query_problem",
            description = "查询缺陷/工单记录。可按部门、版本、状态等维度过滤。")
    public ToolResultBlock queryProblem(
            @ToolParam(name = "department", description = "部门", required = false) String department,
            @ToolParam(name = "version", description = "版本", required = false) String version,
            @ToolParam(name = "status", description = "状态: OPEN/CLOSED/ALL", required = false) String status) {
        // TODO: 接入真实问题/工单库
        return ToolResultBlock.text(
                "Problem 占位: dept=" + department + ", ver=" + version + ", status=" + status);
    }
}
