package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.SqlRegistryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL注册表 Mapper，操作 GaussDB 上的 sql_registry 表。
 *
 * <p>从 MySQL 迁移而来，包路径从 {@code mapper.mysql} 改为 {@code mapper.gauss}，
 * 由 {@link com.agentscopea2a.config.datasource.GaussConfig} 的 {@code @MapperScan} 扫描。
 */
@Mapper
public interface SqlRegistryMapper {

    /**
     * 按 sql_id 查询单条记录 (含 sql_template / params_schema).
     *
     * @param sqlId 业务可读 ID
     * @return 记录; 不存在返回 null
     */
    SqlRegistryEntry selectBySqlId(@Param("sqlId") String sqlId);

    /**
     * 统计指定 sql_id 的记录数 (含禁用记录, 用于唯一性校验).
     * 与 {@link #selectBySqlId} 的区别: 后者带 enabled=1 过滤 (Agent 工具执行用),
     * 唯一性校验必须覆盖禁用记录, 否则把某条改成已禁用记录的同名 sql_id 会漏检.
     *
     * @param sqlId 业务可读 ID
     * @return 命中记录数 (0 表示不冲突)
     */
    int countBySqlId(@Param("sqlId") String sqlId);

    /**
     * 列出所有启用的 SQL (sql_id / 名称 / 描述 / 数据源 / 参数 schema).
     * 排除 sql_template 字段 (体积大, 列表展示不需要).
     */
    List<SqlRegistryEntry> listAllEnabled();

    /**
     * 列出所有记录 (含禁用的), 排除 sql_template.
     */
    List<SqlRegistryEntry> selectAll();

    /**
     * 按 id 查询单条记录 (含 sql_template / params_schema).
     */
    SqlRegistryEntry selectById(@Param("id") Long id);

    /**
     * 新增一条记录. id / created_at / updated_at 由数据库自动生成.
     */
    int insert(SqlRegistryEntry entry);

    /**
     * 按 id 更新记录. updated_at 由触发器自动维护.
     */
    int update(SqlRegistryEntry entry);

    /**
     * 按 id 删除记录.
     */
    int deleteById(@Param("id") Long id);
}
