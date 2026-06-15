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
 * 指定版本数据子智能体桥接工具。
 *
 * <p>把"我要看 xx 版本的数据"类问题转发到下游版本数据 agent。
 */
public class SpecifiedVersionDataAgentTool {

    @Tool(
            name = "specified_version_data_agent",
            description = "调用指定版本数据子智能体,获取某个版本的全维度数据快照。")
    public ToolResultBlock invoke(
            @ToolParam(name = "version", description = "版本号/版本标识,如 '2026年4月份版本'") String version) {
        // TODO: 调用真实版本数据 agent
        return ToolResultBlock.text("SpecifiedVersionData 占位: ver=" + version);
    }
}
