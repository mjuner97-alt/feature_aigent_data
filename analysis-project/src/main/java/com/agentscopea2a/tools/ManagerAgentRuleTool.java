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
 * 管理者规则查询工具。
 *
 * <p>查询管理类的规则配置(KPI/红线/审批阈值等)。
 */
public class ManagerAgentRuleTool {

    @Tool(
            name = "manager_agent_rule",
            description = "查询管理者规则配置(KPI/红线/审批阈值等)。")
    public ToolResultBlock queryRule(
            @ToolParam(name = "rule_key", description = "规则键,如 'defect_red_line' / 'release_kpi'") String ruleKey) {
        // TODO: 接入真实规则中心
        return ToolResultBlock.text("ManagerAgentRule 占位: key=" + ruleKey);
    }
}
