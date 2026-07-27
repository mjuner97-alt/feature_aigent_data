/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.util.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ECharts 图表生成工具 -- 通过 HTTP 调用 LLM, 把数据 JSON 转成 ECharts option JSON。
 *
 * <p>入参: chartType(bar/line) + data(JSON 字符串)。内部用 {@link EChartPrompts} 选取对应
 * 提示词(柱状/折线各自一套生成模板, 运行时只拼接 data), POST 到 OpenAI 兼容的
 * /chat/completions, 解析返回的 ECharts option JSON。标题/轴名/系列等由模型据数据自行生成。
 *
 * <p><b>出错即抛异常</b>: 入参非法抛 {@link IllegalArgumentException}; 环境/LLM 失败抛
 * {@link IllegalStateException}。异常经 {@code router_tool}(已 {@code catch InvocationTargetException}
 * 重抛 {@code IllegalStateException}) 回到框架, 由 {@code ToolMethodInvoker.handleError} 转成
 * {@code ToolResultBlock.error(...)} 回给 LLM, agent 循环不中断。成功路径返回 echarts String。
 *
 * <p>LLM 配置(endpoint/api-key/model/max-tokens)由 application.properties 的
 * {@code harness.echart.llm.*} 注入; 未配置 endpoint 时抛异常, 不影响其它工具。
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建,
 * 经 {@link ToolRoutersIndex#init()} 反射注册, 通过子智能体 {@code router_tool} 暴露。
 */
public class EChartTool {

    private static final Logger log = LoggerFactory.getLogger(EChartTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public EChartTool(String endpoint, String apiKey, String model, int maxTokens) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Tool(
            name = "chart_generate",
            description = "把数据 JSON 字符串转成 ECharts option JSON, 支持 bar(柱状图) / line(折线图)。"
                    + "内部调用 LLM 生成; 提示词只拼接 data, 标题/轴名/系列等由模型根据数据自行生成。"
                    + "入参 chartType=bar|line, data=JSON 字符串。返回 ```echarts 围栏块。")
    public String chartGenerate(
            @ToolParam(name = "chartType",
                    description = "图表类型: bar(柱状图) 或 line(折线图)。大小写不敏感。")
                    String chartType,
            @ToolParam(name = "data",
                    description = "数据 JSON 字符串。任意结构均可, 由 LLM 识别类目与系列并自行生成标题/轴名。")
                    String data) {

        String type = chartType == null ? "" : chartType.trim().toLowerCase();
        if (!type.equals("bar") && !type.equals("line")) {
            throw new IllegalArgumentException(
                    "chart_generate: chartType 必须是 bar 或 line, 实际=" + chartType);
        }
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("chart_generate: data 为空。");
        }
        // 快速校验 data 是合法 JSON, 避免浪费一次 LLM 调用
        try {
            MAPPER.readTree(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("chart_generate: data 不是合法 JSON: " + e.getMessage(), e);
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "chart_generate: 未配置 LLM 端点, 请在 application.properties 设置 "
                            + "harness.echart.llm.endpoint / .api-key / .model。");
        }

        String prompt = type.equals("bar") ? EChartPrompts.bar(data) : EChartPrompts.line(data);
        log.info("chart_generate: type={}, dataLen={}, 调用 LLM", type, data.length());

        String llmResp;
        try {
            llmResp = callLlm(prompt);
        } catch (Exception e) {
            log.error("chart_generate: LLM 调用失败", e);
            throw new IllegalStateException("chart_generate: LLM 调用失败: " + e.getMessage(), e);
        }
        if (llmResp == null || llmResp.isBlank()) {
            throw new IllegalStateException("chart_generate: LLM 返回为空。");
        }

        String content = extractContent(llmResp);
        if (content == null) {
            throw new IllegalStateException(
                    "chart_generate: 无法从 LLM 响应解析 content。原始响应: " + truncate(llmResp, 500));
        }

        String normalized = validateAndPretty(stripFences(content));
        if (normalized == null) {
            throw new IllegalStateException(
                    "chart_generate: LLM 输出不是合法 JSON 对象。原始输出: " + truncate(content, 500));
        }
        log.info("chart_generate: 生成成功, type={}, optionLen={}", type, normalized.length());
        return "已生成 " + ("bar".equals(type) ? "柱状图" : "折线图")
                + " 的 ECharts option:\n\n```echarts\n" + normalized + "\n```";
    }

    // ==================== LLM HTTP 调用 (OpenAI 兼容 /chat/completions) ====================

    private String callLlm(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String jsonBody = MAPPER.writeValueAsString(body);

        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return HttpClient.postJson(endpoint, jsonBody, headers);
    }

    /** 从 OpenAI 兼容响应 {choices:[{message:{content}}]} 提取 content。 */
    private static String extractContent(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) return content.asText();
            // 容忍个别网关把结果直接放在 content 字段
            JsonNode alt = root.path("content");
            return alt.isTextual() ? alt.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 LLM 可能包裹的 ```json / ```echarts 围栏。 */
    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    /** 校验是 JSON 对象并格式化; 非法返回 null。 */
    private static String validateAndPretty(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) return null;
            return PRETTY.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
