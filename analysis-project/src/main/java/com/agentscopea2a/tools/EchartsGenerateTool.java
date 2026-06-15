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
 * 生成 ECharts 图表配置工具。
 *
 * <p>根据传入的数据与图表类型,产出 ECharts option JSON,供前端直接渲染。
 */
public class EchartsGenerateTool {

    @Tool(
            name = "generate_echarts",
            description = "根据数据和图表类型生成 ECharts option JSON。常用图表类型:bar / line / pie / scatter。")
    public ToolResultBlock generateEcharts(
            @ToolParam(name = "chart_type", description = "图表类型: bar/line/pie/scatter") String chartType,
            @ToolParam(name = "data_json", description = "图表数据,JSON 格式字符串") String dataJson,
            @ToolParam(name = "title", description = "图表标题", required = false) String title) {
        // TODO: 真正生成 ECharts option JSON
        return ToolResultBlock.text(
                "ECharts 占位: type=" + chartType + ", title=" + title + ", data=" + dataJson);
    }
}
