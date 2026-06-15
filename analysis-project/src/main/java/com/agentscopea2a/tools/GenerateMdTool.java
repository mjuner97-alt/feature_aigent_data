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
 * 生成 Markdown 文本/报告工具。
 *
 * <p>根据传入的数据/对话内容,组装为标准格式的 Markdown 文档。
 */
public class GenerateMdTool {

    @Tool(
            name = "generate_md",
            description = "把给定的数据/分析结果格式化为 Markdown 文档。返回 Markdown 全文。")
    public ToolResultBlock generateMarkdown(
            @ToolParam(name = "title", description = "文档标题") String title,
            @ToolParam(name = "content", description = "文档主体内容(可为结构化 JSON 或自由文本)") String content) {
        // TODO: 接入真实 Markdown 模板渲染
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n").append(content).append("\n");
        return ToolResultBlock.text(sb.toString());
    }
}
