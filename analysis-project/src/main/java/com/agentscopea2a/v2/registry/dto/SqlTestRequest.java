package com.agentscopea2a.v2.registry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * SQL 测试请求 DTO.
 *
 * <p>前端直接传入 sql_template / datasource / params_schema / params,
 * 不需要后端再查 sql_registry 表.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlTestRequest {
    /** SQL 模板, 含 :param 命名占位符 */
    private String sqlTemplate;
    /** 目标数据源: mysql / gauss / clickhouse */
    private String datasource;
    /** 参数定义 JSON, 如 [{"name":"userId","type":"string","required":true}] */
    private String paramsSchema;
    /** SQL 模板参数, 如 {"userId":"alice","startTime":"2026-07-01"} */
    private Map<String, Object> params;
}
