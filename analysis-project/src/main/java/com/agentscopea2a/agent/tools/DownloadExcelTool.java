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
 * 导出 Excel 文件工具。
 *
 * <p>把指定查询结果序列化为 .xlsx 文件,返回可下载链接 / 文件路径。
 */
public class DownloadExcelTool {

    @Tool(
            name = "download_excel",
            description = "把指定的查询结果导出为 Excel 文件。返回生成的 Excel 路径或下载链接。")
    public ToolResultBlock downloadExcel(
            @ToolParam(name = "data_ref", description = "数据引用键(由前序查询工具返回的 ID/key)") String dataRef,
            @ToolParam(name = "filename", description = "导出的文件名(不含扩展名)", required = false) String filename) {
        // TODO: 用 EasyExcel / POI 实现真实导出
        return ToolResultBlock.text("Excel 导出占位: data=" + dataRef + ", file=" + filename);
    }
}
