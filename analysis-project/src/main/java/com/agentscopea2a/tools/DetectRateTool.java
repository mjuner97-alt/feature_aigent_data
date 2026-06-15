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
 * 缺陷检出率 / 命中率类指标查询工具。
 */
public class DetectRateTool {

    @Tool(
            name = "query_detect_rate",
            description = "查询指定部门/版本/季度下的缺陷检出率(发现率)指标。")
    public ToolResultBlock queryDetectRate(
            @ToolParam(name = "department", description = "部门名称") String department,
            @ToolParam(name = "period", description = "时间周期,如 '2026年1季度' 或 '2026年4月份版本'") String period) {
        // TODO: 接入真实数据源
        return ToolResultBlock.text("DetectRate 占位: dept=" + department + ", period=" + period);
    }
}
