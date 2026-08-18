package com.agentscopea2a.v2.registry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SQL 测试结果 DTO.
 *
 * <p>与 {@link com.agentscopea2a.v2.tools.SqlRegistryExecTool} 的 markdown 输出不同,
 * 本 DTO 返回结构化 JSON, 供前端 el-table 渲染.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlTestResult {
    private boolean success;
    /** 错误信息, success=false 时有值 */
    private String error;
    /** 列名列表 */
    private List<String> columns;
    /** 行数据, 每行是 column→value 的 Map */
    private List<Map<String, Object>> rows;
    /** 总行数 */
    private int totalRows;
    /** 执行耗时 (ms) */
    private long elapsedMs;
    /** 目标数据源 */
    private String datasource;
    /** sql_id */
    private String sqlId;
}
