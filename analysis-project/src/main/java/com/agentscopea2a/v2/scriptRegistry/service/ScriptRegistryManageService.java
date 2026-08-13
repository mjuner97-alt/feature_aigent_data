package com.agentscopea2a.v2.scriptRegistry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Python 脚本注册表管理服务.
 *
 * <p>提供 CRUD, 与 {@link com.agentscopea2a.v2.sqlRegistry.service.SqlRegistryManageService}
 * 同构 (不含试运行, 脚本执行留给 agent 工具 {@code script_exec}).
 *
 * <p>{@code datasources} 是 JSON 数组字符串 (如 {@code ["gauss","mysql"]}),
 * 列表筛选按子串包含匹配 (适合管理页输入框).
 */
@Service
public class ScriptRegistryManageService {

    private final ScriptRegistryMapper mapper;

    public ScriptRegistryManageService(ScriptRegistryMapper mapper) {
        this.mapper = mapper;
    }

    // ======================================================================
    // CRUD
    // ======================================================================

    /**
     * 列表 (含禁用记录), 可选按 datasource / createdBy 筛选.
     * datasource 子串匹配 (忽略大小写, 因 datasources 是 JSON 数组字符串如 ["gauss","mysql"]);
     * createdBy 模糊匹配 (忽略大小写, 适合输入框).
     */
    public List<ScriptRegistryEntry> list(String datasource, String createdBy) {
        return mapper.selectAll().stream()
                .filter(e -> datasource == null || datasource.isBlank()
                        || (e.getDatasources() != null
                                && e.getDatasources().toLowerCase().contains(datasource.toLowerCase())))
                .filter(e -> createdBy == null || createdBy.isBlank()
                        || (e.getCreatedBy() != null
                                && e.getCreatedBy().toLowerCase().contains(createdBy.toLowerCase())))
                .collect(Collectors.toList());
    }

    public ScriptRegistryEntry getById(Long id) {
        return mapper.selectById(id);
    }

    public ScriptRegistryEntry getByScriptId(String scriptId) {
        return mapper.selectByScriptId(scriptId);
    }

    @Transactional("gaussTransactionManager")
    public ScriptRegistryEntry create(ScriptRegistryEntry entry, String userId) {
        // 校验 script_id 非空 (拼路径依赖) + 唯一性
        if (entry.getScriptId() == null || entry.getScriptId().isBlank()) {
            throw new IllegalArgumentException("script_id 不能为空");
        }
        ScriptRegistryEntry existing = mapper.selectByScriptId(entry.getScriptId());
        if (existing != null) {
            throw new IllegalArgumentException("script_id '" + entry.getScriptId() + "' 已存在");
        }

        entry.setCreatedBy(userId);
        // 脚本路径由后端按 userId + scriptId + ".py" 拼接, 不接受前端传入
        entry.setScriptPath(buildScriptPath(userId, entry.getScriptId()));
        if (entry.getEnabled() == null) {
            entry.setEnabled(1);
        }
        if (entry.getTimeoutSeconds() == null) {
            entry.setTimeoutSeconds(60);
        }
        if (entry.getDatasources() == null || entry.getDatasources().isBlank()) {
            entry.setDatasources("[\"gauss\"]");
        }
        if (entry.getParamsSchema() == null || entry.getParamsSchema().isBlank()) {
            entry.setParamsSchema("[]");
        }
        mapper.insert(entry);
        return entry;
    }

    @Transactional("gaussTransactionManager")
    public ScriptRegistryEntry update(Long id, ScriptRegistryEntry patch) {
        ScriptRegistryEntry existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }

        // 如果 script_id 有变更, 检查唯一性
        if (patch.getScriptId() != null && !patch.getScriptId().equals(existing.getScriptId())) {
            ScriptRegistryEntry byScriptId = mapper.selectByScriptId(patch.getScriptId());
            if (byScriptId != null && !byScriptId.getId().equals(id)) {
                throw new IllegalArgumentException("script_id '" + patch.getScriptId() + "' 已存在");
            }
        }

        // 选择性更新: 非 null 字段覆盖 (script_path 不接受前端传入, 由 createdBy + scriptId 派生)
        if (patch.getScriptId() != null) {
            existing.setScriptId(patch.getScriptId());
            existing.setScriptPath(buildScriptPath(existing.getCreatedBy(), existing.getScriptId()));
        }
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getDatasources() != null) existing.setDatasources(patch.getDatasources());
        if (patch.getParamsSchema() != null) existing.setParamsSchema(patch.getParamsSchema());
        if (patch.getTimeoutSeconds() != null) existing.setTimeoutSeconds(patch.getTimeoutSeconds());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());

        mapper.update(existing);
        return existing;
    }

    @Transactional("gaussTransactionManager")
    public void delete(Long id) {
        ScriptRegistryEntry existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }
        mapper.deleteById(id);
    }

    /** 脚本相对路径 = userId + scriptId + ".py" (相对 workspace/scripts/, 由后端拼接, 前端不参与). */
    private String buildScriptPath(String userId, String scriptId) {
        return userId + scriptId + ".py";
    }
}
