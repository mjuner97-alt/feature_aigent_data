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
     * 列出所有启用的脚本 (script_id / 名称 / 描述 / 数据源 / 参数 schema / 超时).
     * 不返回 description 全文 (体积大, 列表展示用 name 即可).
     */
    List<ScriptRegistryEntry> listAllEnabled();
}
