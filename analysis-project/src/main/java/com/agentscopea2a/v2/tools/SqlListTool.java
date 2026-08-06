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

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 列出所有预注册 SQL 的 sql_id + 名称 + 描述 + 数据源 + 参数 schema.
 *
 * <p>辅助工具, 给 LLM 决策"调哪个 sql_id"用. 不执行 SQL, 只读 sql_registry 配置表.
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 bean,
 * 注入 {@link SqlRegistryMapper}.
 */
public class SqlListTool {

    private static final Logger log = LoggerFactory.getLogger(SqlListTool.class);

    private final SqlRegistryMapper registryMapper;

    public SqlListTool(SqlRegistryMapper registryMapper) {
        this.registryMapper = registryMapper;
    }

    @Tool(
            name = "sql_list",
            description = "列出所有预注册 SQL 的 sql_id + 名称 + 描述 + 数据源 + 参数 schema. "
                    + "用此工具查可用 sql_id 后再调 sql_registry_exec. 不执行 SQL, 只读配置表.")
    public ToolResultBlock sqlList() {
        if (registryMapper == null) {
            return ToolResultBlock.text("sql_list 不可用: registryMapper 未注入 (检查 SqlRegistryMapper bean)");
        }

        long start = System.currentTimeMillis();
        List<SqlRegistryEntry> entries;
        try {
            entries = registryMapper.listAllEnabled();
        } catch (Exception e) {
            log.error("sql_list 查询失败", e);
            return ToolResultBlock.text("sql_list 查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (entries == null || entries.isEmpty()) {
            return ToolResultBlock.text("sql_registry 表中无启用的记录. 请业务方/DBA INSERT 一条 sql_registry 记录.");
        }

        StringBuilder md = new StringBuilder();
        md.append("[sql_list] 共 ").append(entries.size()).append(" 条启用 SQL\n\n");
        md.append("| sql_id | name | datasource | params_schema (name/type/required/desc) |\n");
        md.append("|---|---|---|---|\n");
        for (SqlRegistryEntry e : entries) {
            md.append("| `").append(e.getSqlId()).append("` | ")
                    .append(nullSafe(e.getName())).append(" | ")
                    .append(nullSafe(e.getDatasource())).append(" | ")
                    .append(formatParams(e.getParamsSchema())).append(" |\n");
        }
        md.append("\n[sql_list] 耗时 ").append(System.currentTimeMillis() - start).append(" ms");
        return ToolResultBlock.text(md.toString());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /**
     * 把 params_schema JSON 简化成单行表格内描述.
     * 输入: [{"name":"userId","type":"string","required":true,"description":"用户 ID"}]
     * 输出: userId (string, 必填, 用户 ID)
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
            return "(params_schema 解析失败, 见 sql_registry 表)";
        }
    }
}
