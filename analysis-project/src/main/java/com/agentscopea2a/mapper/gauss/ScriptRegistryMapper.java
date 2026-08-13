package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Python 指标脚本注册表 Mapper，操作 GaussDB 上的 script_registry 表。
 *
 * <p>从 MySQL 迁移而来，包路径从 {@code mapper.mysql} 改为 {@code mapper.gauss}，
 * 由 {@link com.agentscopea2a.config.datasource.GaussConfig} 的 {@code @MapperScan} 扫描。
 *
 * <p>与 {@link SqlRegistryMapper} 同构，便于复用 script_list/script_exec 的调用模式。
 */
@Mapper
public interface ScriptRegistryMapper {

    /**
     * 按 script_id 查询单条记录 (含 script_path / params_schema / datasources).
     *
     * @param scriptId 业务可读 ID
     * @return 记录; 不存在或禁用返回 null
     */
    ScriptRegistryEntry selectByScriptId(@Param("scriptId") String scriptId);

    /**
     * 统计指定 script_id 的记录数 (含禁用记录, 用于唯一性校验).
     * 与 {@link #selectByScriptId} 的区别: 后者带 enabled=1 过滤 (Agent 工具执行用),
     * 唯一性校验必须覆盖禁用记录, 否则把某条改成已禁用记录的同名 script_id 会漏检.
     *
     * @param scriptId 业务可读 ID
     * @return 命中记录数 (0 表示不冲突)
     */
    int countByScriptId(@Param("scriptId") String scriptId);

    /**
     * 列出所有启用的脚本 (script_id / 名称 / 描述 / 数据源 / 参数 schema / 超时).
     * 不返回 description 全文 (体积大, 列表展示用 name 即可).
     */
    List<ScriptRegistryEntry> listAllEnabled();

    /**
     * 管理页面: 列出所有记录 (含禁用, 排除 params_schema 体积大的字段).
     */
    List<ScriptRegistryEntry> selectAll();

    /**
     * 管理页面: 按 id 查询详情 (含 params_schema).
     */
    ScriptRegistryEntry selectById(@Param("id") Long id);

    /**
     * 新增一条记录. id / created_at / updated_at 由数据库自动生成.
     */
    int insert(ScriptRegistryEntry entry);

    /**
     * 按 id 更新记录. updated_at 由触发器自动维护.
     */
    int update(ScriptRegistryEntry entry);

    /**
     * 按 id 删除记录.
     */
    int deleteById(@Param("id") Long id);
}
