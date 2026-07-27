/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.tools;

/**
 * chart_generate 的提示词模板。
 *
 * <p>柱状图与折线图各自一套独立的"生成模板"+规则。运行时唯一动态拼接的参数是 {@code data}
 * (用户数据 JSON 字符串); 标题/轴名/图例/系列命名/配色等全部由模型根据数据语义自行生成。
 * {@link EChartTool} 按 chartType 选用 bar 或 line 模板。
 */
final class EChartPrompts {

    private EChartPrompts() {}

    // ==================== 柱状图 ====================

    /** 柱状图提示词; 仅拼接 data。 */
    static String bar(String data) {
        return "你是一个 ECharts option 生成器。请根据【用户数据】生成一段合法的 ECharts 柱状图(bar) option JSON。\n\n"
                + "## 用户数据(JSON)\n" + data + "\n\n"
                + "## 生成模板(必须严格遵循此结构, 字段可按需增减)\n"
                + "{\n"
                + "  \"title\": { \"text\": \"<根据数据语义生成的标题>\", \"left\": \"center\" },\n"
                + "  \"tooltip\": { \"trigger\": \"axis\" },\n"
                + "  \"legend\": { \"data\": [\"<系列名>\", \"...\"], \"bottom\": 0 },\n"
                + "  \"grid\": { \"left\": 48, \"right\": 24, \"bottom\": 48, \"top\": 32, \"containLabel\": true },\n"
                + "  \"xAxis\": { \"type\": \"category\", \"data\": [\"<类目>\", \"...\"] },\n"
                + "  \"yAxis\": { \"type\": \"value\" },\n"
                + "  \"series\": [\n"
                + "    { \"name\": \"<系列名>\", \"type\": \"bar\", \"data\": [0, 0],"
                + " \"itemStyle\": { \"borderRadius\": [4, 4, 0, 0] } }\n"
                + "  ]\n"
                + "}\n\n"
                + "## 规则\n"
                + "1. 只输出 JSON 对象本身, 不要 ```代码块, 不要任何解释文字。\n"
                + "2. series[].type 必须全部为 \"bar\"。\n"
                + "3. 从【用户数据】中识别类目(放 xAxis.data)与系列(每个系列一个 name + 数值数组 data)。数据值必须是数字。\n"
                + "4. 用户数据可能是预型({xAxis, series})、记录型(行数组)或其它结构, 请自行理解并正确转换。\n"
                + "5. 标题、轴名、图例、系列命名、配色等请根据数据语义自行生成, 不要保留 <占位符>。\n"
                + "6. 柱状图适合类目对比; 多系列时同各类目并列。仅当数据语义适合才加 \"stack\" 做堆叠。\n"
                + "7. 输出的 JSON 必须能被 JSON.parse 正确解析。\n\n"
                + "请直接输出 option JSON:";
    }

    // ==================== 折线图 ====================

    /** 折线图提示词; 仅拼接 data。 */
    static String line(String data) {
        return "你是一个 ECharts option 生成器。请根据【用户数据】生成一段合法的 ECharts 折线图(line) option JSON。\n\n"
                + "## 用户数据(JSON)\n" + data + "\n\n"
                + "## 生成模板(必须严格遵循此结构, 字段可按需增减)\n"
                + "{\n"
                + "  \"title\": { \"text\": \"<根据数据语义生成的标题>\", \"left\": \"center\" },\n"
                + "  \"tooltip\": { \"trigger\": \"axis\" },\n"
                + "  \"legend\": { \"data\": [\"<系列名>\", \"...\"], \"bottom\": 0 },\n"
                + "  \"grid\": { \"left\": 48, \"right\": 24, \"bottom\": 48, \"top\": 32, \"containLabel\": true },\n"
                + "  \"xAxis\": { \"type\": \"category\", \"boundaryGap\": false,"
                + " \"data\": [\"<类目>\", \"...\"] },\n"
                + "  \"yAxis\": { \"type\": \"value\" },\n"
                + "  \"series\": [\n"
                + "    { \"name\": \"<系列名>\", \"type\": \"line\", \"data\": [0, 0],"
                + " \"smooth\": true, \"lineStyle\": { \"width\": 2 },"
                + " \"itemStyle\": {}, \"areaStyle\": { \"opacity\": 0.1 } }\n"
                + "  ]\n"
                + "}\n\n"
                + "## 规则\n"
                + "1. 只输出 JSON 对象本身, 不要 ```代码块, 不要任何解释文字。\n"
                + "2. series[].type 必须全部为 \"line\"。\n"
                + "3. 从【用户数据】中识别类目(放 xAxis.data, 通常是时间或有序维度)与系列"
                + "(每个系列一个 name + 数值数组 data)。数据值必须是数字。\n"
                + "4. 用户数据可能是预型({xAxis, series})、记录型(行数组)或其它结构, 请自行理解并正确转换。\n"
                + "5. 标题、轴名、图例、系列命名、配色等请根据数据语义自行生成, 不要保留 <占位符>。\n"
                + "6. 折线图适合趋势; 数据点按类目顺序连接; 可用 smooth 平滑、areaStyle 面积填充; "
                + "类目较多时给 xAxis.axisLabel 加 \"rotate\": 45。\n"
                + "7. 输出的 JSON 必须能被 JSON.parse 正确解析。\n\n"
                + "请直接输出 option JSON:";
    }
}
