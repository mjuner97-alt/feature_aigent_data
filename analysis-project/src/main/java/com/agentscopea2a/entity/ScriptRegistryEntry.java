package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Python 指标计算脚本注册表实体 - 一条预审过的 Python 脚本记录.
 *
 * <p>对应 MySQL 业务库 {@code script_registry} 表. 由开发人员维护, 工具运行时按
 * {@code scriptId} 查询取 {@code scriptPath} + {@code paramsSchema} + {@code datasources},
 * 在 plan-b 容器内 {@code subprocess.run(["python3", scriptPath])} 同容器 fork 执行.
 *
 * <p>与 {@link SqlRegistryEntry} 的区别:
 * <ul>
 *   <li>{@code sql_registry}: 只执行 SQL, 返回 markdown 表 (不计算)
 *   <li>{@code script_registry}: 执行 Python 脚本, 脚本内完成 SQL 取数 + pandas 算指标
 * </ul>
 *
 * <p>{@code datasources} 是 JSON 数组字符串 (如 {@code ["gauss","mysql"]}), 支持
 * 跨库 join 场景. {@code ScriptExecTool} 解析后遍历注入对应 DB URL 环境变量,
 * 未声明的库不注入 (最小权限).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptRegistryEntry {
    private Long id;
    private String scriptId;
    private String name;
    private String description;
    /** 脚本相对路径, 相对 /app/workspace/scripts/ */
    private String scriptPath;
    /** JSON 数组字符串, 如 ["gauss"] 或 ["gauss","mysql"] */
    private String datasources;
    /** 参数定义 JSON 字符串 */
    private String paramsSchema;
    /** 执行超时 (秒), 硬上限 300 */
    private Integer timeoutSeconds;
    /** 0=禁用 1=启用 */
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    /** 创建人姓名, 仅用于管理接口响应, 不映射 script_registry 表字段. */
    private String createdByName;
}
