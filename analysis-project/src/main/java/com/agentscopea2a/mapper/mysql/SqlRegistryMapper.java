package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.SqlRegistryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL注册表 Mapper，操作 sql_registry 表 */
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
     * 列出所有启用的 SQL (sql_id / 名称 / 描述 / 数据源 / 参数 schema).
     * 排除 sql_template 字段 (体积大, 列表展示不需要).
     */
    List<SqlRegistryEntry> listAllEnabled();
}
