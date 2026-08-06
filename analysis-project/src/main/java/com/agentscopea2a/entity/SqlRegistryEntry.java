package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SQL 注册表实体 - 一条预审过的 SQL 模板记录.
 *
 * <p>对应 GaussDB 业务库 {@code sql_registry} 表. 由 DBA 维护, 工具运行时按 {@code sqlId} 查询.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlRegistryEntry {
    private Long id;
    private String sqlId;
    private String name;
    private String description;
    /** 目标数据源: mysql / gauss / clickhouse */
    private String datasource;
    /** SQL 模板, :param 命名占位符 */
    private String sqlTemplate;
    /** 参数定义 JSON: [{"name":"...","type":"...","required":bool,"description":"..."}] */
    private String paramsSchema;
    /** 0=禁用 1=启用 */
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
