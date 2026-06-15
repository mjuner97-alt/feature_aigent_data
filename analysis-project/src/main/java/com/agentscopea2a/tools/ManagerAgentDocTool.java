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
 * 管理者文档查询/检索工具。
 *
 * <p>面向管理者智能体,提供从内部文档库检索制度/规范的能力。
 */
public class ManagerAgentDocTool {

    @Tool(
            name = "manager_agent_doc",
            description = "在管理者文档库中检索制度/规范文档。返回最相关的若干文档片段。")
    public ToolResultBlock searchDoc(
            @ToolParam(name = "query", description = "检索关键词或自然语言问题") String query,
            @ToolParam(name = "top_k", description = "返回前 K 条结果", required = false) Integer topK) {
        // TODO: 接入向量库 / ES 检索
        return ToolResultBlock.text("ManagerAgentDoc 占位: q=" + query + ", topK=" + topK);
    }
}
