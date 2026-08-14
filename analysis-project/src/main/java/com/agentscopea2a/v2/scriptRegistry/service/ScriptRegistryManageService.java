package com.agentscopea2a.v2.scriptRegistry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final MockOrgService mockOrgService;

    public ScriptRegistryManageService(ScriptRegistryMapper mapper, MockOrgService mockOrgService) {
        this.mapper = mapper;
        this.mockOrgService = mockOrgService;
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
        List<ScriptRegistryEntry> entries = mapper.selectAll().stream()
                .filter(e -> datasource == null || datasource.isBlank()
                        || (e.getDatasources() != null
                                && e.getDatasources().toLowerCase().contains(datasource.toLowerCase())))
                .filter(e -> createdBy == null || createdBy.isBlank()
                        || (e.getCreatedBy() != null
                                && e.getCreatedBy().toLowerCase().contains(createdBy.toLowerCase())))
                .collect(Collectors.toList());
        fillCreatedByName(entries);
        return entries;
    }

    /**
     * 按 createdBy 批量查 developer_pl_person_info 姓名, 回填 createdByName (展示用)。
     * 查不到的 userId 不在 Map 中, createdByName 留空, 前端回退到仅显示 userId。
     */
    private void fillCreatedByName(List<ScriptRegistryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<String> userIds = entries.stream()
                .map(ScriptRegistryEntry::getCreatedBy)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<String, String> nameMap = mockOrgService.getUserNameMap(userIds);
        for (ScriptRegistryEntry e : entries) {
            e.setCreatedByName(nameMap.get(e.getCreatedBy()));
        }
    }

    public ScriptRegistryEntry getById(Long id) {
        return mapper.selectById(id);
    }

    public ScriptRegistryEntry getByScriptId(String scriptId) {
        return mapper.selectByScriptId(scriptId);
    }

    @Transactional("gaussTransactionManager")
    public ScriptRegistryEntry create(ScriptRegistryEntry entry, String userId) {
        // 校验 script_id 非空 (拼路径依赖) + 唯一性 (含禁用记录, 防止注册乱象下重复)
        if (entry.getScriptId() == null || entry.getScriptId().isBlank()) {
            throw new IllegalArgumentException("script_id 不能为空");
        }
        if (mapper.countByScriptId(entry.getScriptId()) > 0) {
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

        // 如果 script_id 有变更, 检查唯一性 (含禁用记录)
        if (patch.getScriptId() != null && !patch.getScriptId().equals(existing.getScriptId())) {
            if (mapper.countByScriptId(patch.getScriptId()) > 0) {
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
