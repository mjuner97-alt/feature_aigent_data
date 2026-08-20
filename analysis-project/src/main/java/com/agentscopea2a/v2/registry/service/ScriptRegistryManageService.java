package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Python 脚本注册表管理服务.
 *
 * <p>提供 CRUD, 与 {@link SqlRegistryManageService}
 * 同构 (不含试运行, 脚本执行留给 agent 工具 {@code script_exec}).
 *
 * <p>{@code datasources} 是 JSON 数组字符串 (如 {@code ["gauss","mysql"]}),
 * 列表筛选按子串包含匹配 (适合管理页输入框).
 */
@Service
public class ScriptRegistryManageService {

    private final ScriptRegistryMapper mapper;
    private final DeveloperPlPersonInfoMapper personInfoMapper;

    public ScriptRegistryManageService(ScriptRegistryMapper mapper,
                                       DeveloperPlPersonInfoMapper personInfoMapper) {
        this.mapper = mapper;
        this.personInfoMapper = personInfoMapper;
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
        populateCreatedByNames(entries);
        return entries;
    }

    private void populateCreatedByNames(List<ScriptRegistryEntry> entries) {
        List<String> userIds = entries.stream()
                .map(ScriptRegistryEntry::getCreatedBy)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (userIds.isEmpty()) return;

        List<DeveloperPlPersonInfo> people = personInfoMapper.selectByUserIds(userIds);
        Map<String, String> namesByUserId = new HashMap<>();
        if (people != null) {
            for (DeveloperPlPersonInfo person : people) {
                if (person.getName() != null && !person.getName().isBlank()) {
                    if (person.getUserId() != null && !person.getUserId().isBlank()) {
                        namesByUserId.putIfAbsent(person.getUserId(), person.getName());
                    }
                    if (person.getLoginUserId() != null && !person.getLoginUserId().isBlank()) {
                        namesByUserId.putIfAbsent(person.getLoginUserId(), person.getName());
                    }
                }
            }
        }
        for (ScriptRegistryEntry entry : entries) {
            entry.setCreatedByName(namesByUserId.get(entry.getCreatedBy()));
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
        // 脚本路径由后端按 {userId}/{scriptId}.py 拼接, 不接受前端传入
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

    /** 脚本相对路径 = {userId}/{scriptId}.py (相对 workspace/scripts/, 由后端拼接, 前端不参与)。
     *  分隔符必须显式写 "/",漏掉会把 userId 和 scriptId 连成一串,ScriptExecTool 按
     *  workspace/scripts/{userId}/{scriptId}.py 找不到文件。 */
    private String buildScriptPath(String userId, String scriptId) {
        return userId + "/" + scriptId + ".py";
    }
}
