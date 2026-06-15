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
 * 数学计算工具 —— 表达式求值 / 单位换算 / 统计指标等。
 */
public class CalculationTool {

    @Tool(
            name = "calculate",
            description = "计算一个数学表达式并返回结果。支持四则运算、括号、常见函数(后续接入表达式引擎)。")
    public ToolResultBlock calculate(
            @ToolParam(name = "expression", description = "数学表达式,如 '(3+5)*2'") String expression) {
        // TODO: 接入表达式引擎(例如 Hutool 的 ScriptUtil 或 mvel)替换占位实现
        return ToolResultBlock.text("计算占位: " + expression);
    }
}
