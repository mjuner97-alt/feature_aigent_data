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

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 列出所有预注册 Python 指标脚本的 script_id + 名称 + 描述 + 数据源 + 参数 schema + 超时.
 *
 * <p>辅助工具, 给 LLM 决策"调哪个 script_id"用. 不执行脚本, 只读 script_registry 配置表.
 * 与 {@link SqlListTool} 同构, 便于 LLM 复用 sql_list/sql_registry_exec 的调用经验.
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 bean,
 * 注入 {@link ScriptRegistryMapper}.
 */
public class ScriptListTool {

    private static final Logger log = LoggerFactory.getLogger(ScriptListTool.class);

    private final ScriptRegistryMapper registryMapper;

    public ScriptListTool(ScriptRegistryMapper registryMapper) {
        this.registryMapper = registryMapper;
    }

    @Tool(
            name = "script_list",
            description = "列出所有预注册 Python 指标脚本的 script_id + 名称 + 描述 + 数据源 + 参数 schema. "
                    + "用此工具查可用 script_id 后再调 script_exec. 不执行脚本, 只读配置表. "
                    + "script_exec 在 plan-b 容器内同进程 fork python3, 一次完成 SQL 取数 + pandas 算指标, "
                    + "替代 sql_registry_exec + python_exec 两步走.")
    public ToolResultBlock scriptList() {
        if (registryMapper == null) {
            return ToolResultBlock.text("script_list 不可用: registryMapper 未注入 (检查 ScriptRegistryMapper bean)");
        }

        long start = System.currentTimeMillis();
        List<ScriptRegistryEntry> entries;
        try {
            entries = registryMapper.listAllEnabled();
        } catch (Exception e) {
            log.error("script_list 查询失败", e);
            return ToolResultBlock.text("script_list 查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (entries == null || entries.isEmpty()) {
            return ToolResultBlock.text("script_registry 表中无启用的记录. 请开发人员 INSERT 一条 script_registry 记录 + 部署 .py 脚本到 workspace/scripts/.");
        }

        StringBuilder md = new StringBuilder();
        md.append("[script_list] 共 ").append(entries.size()).append(" 条启用脚本\n\n");
        md.append("| script_id | name | datasources | timeout | params_schema (name/type/required/desc) |\n");
        md.append("|---|---|---|---:|---|\n");
        for (ScriptRegistryEntry e : entries) {
            md.append("| `").append(e.getScriptId()).append("` | ")
                    .append(nullSafe(e.getName())).append(" | ")
                    .append(nullSafe(e.getDatasources())).append(" | ")
                    .append(e.getTimeoutSeconds() == null ? 60 : e.getTimeoutSeconds()).append("s | ")
                    .append(formatParams(e.getParamsSchema())).append(" |\n");
        }
        md.append("\n[script_list] 耗时 ").append(System.currentTimeMillis() - start).append(" ms");
        return ToolResultBlock.text(md.toString());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /**
     * 把 params_schema JSON 简化成单行表格内描述.
     * 输入: [{"name":"dept","type":"string","required":true,"description":"开发部门"}]
     * 输出: dept (string, 必填, 开发部门)
     */
    private static String formatParams(String paramsSchemaJson) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank() || "[]".equals(paramsSchemaJson.trim())) {
            return "(无参数)";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<?> list = mapper.readValue(paramsSchemaJson, List.class);
            if (list.isEmpty()) return "(无参数)";
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                String name = String.valueOf(m.get("name"));
                String type = String.valueOf(m.get("type"));
                boolean required = Boolean.TRUE.equals(m.get("required"));
                String desc = m.get("description") == null ? "" : String.valueOf(m.get("description"));
                if (sb.length() > 0) sb.append("<br>");
                sb.append("`").append(name).append("` ")
                        .append("(").append(type).append(", ")
                        .append(required ? "必填" : "可选").append(", ")
                        .append(desc).append(")");
            }
            return sb.toString().replace("|", "\\|").replace("\n", " ");
        } catch (Exception e) {
            log.warn("formatParams 解析失败: {}", paramsSchemaJson, e);
            return "(params_schema 解析失败, 见 script_registry 表)";
        }
    }
}
