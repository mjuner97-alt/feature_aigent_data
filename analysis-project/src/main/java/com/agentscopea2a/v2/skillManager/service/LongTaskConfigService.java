package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.entity.LongTaskType;
import com.agentscopea2a.v2.skillManager.mapper.LongTaskConfigMapper;
import com.agentscopea2a.v2.audit.ResourceChangeAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 长任务流程类型业务层：负责参数校验、业务规则和事务。 */
@Service
public class LongTaskConfigService {
    private final LongTaskConfigMapper mapper;
    private final ResourceChangeAuditService auditService;
    public LongTaskConfigService(LongTaskConfigMapper mapper, ResourceChangeAuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /** 查询类型列表并转换为接口响应。 */
    public List<LongTaskTypeResponse> listTypes(Boolean enabled) {
        return mapper.selectTypes(enabled).stream().map(this::toResponse).toList();
    }

    /** 分页查询；页码从 1 开始，单页最多返回 100 条。 */
    public LongTaskTypePageResponse listTypesPage(Boolean enabled, int page, int pageSize) {
        int safePage = Math.max(1, page), safePageSize = Math.min(100, Math.max(1, pageSize));
        var items = mapper.selectTypesPage(enabled, (safePage - 1) * safePageSize, safePageSize)
                .stream().map(this::toResponse).toList();
        return new LongTaskTypePageResponse(items, mapper.countTypes(enabled), safePage, safePageSize);
    }

    /** 创建类型：校验名称、排序唯一性，并设置默认启用状态。 */
    @Transactional
    public LongTaskTypeResponse createType(LongTaskTypeRequest request, String user) {
        String name = normalizeName(request.name());
        int sort = validSort(request.sortOrder());
        ensureUniqueSort(null, sort);
        if (mapper.selectTypeByName(name) != null) throw new IllegalArgumentException("LongTaskTypeNameAlreadyExists");
        var type = new LongTaskType(null, name, request.description(), sort,
                request.enabled() == null || request.enabled(), null, null);
        mapper.insertType(type);
        auditService.record("long_task_flow", String.valueOf(type.getId()), type.getName(), "create",
                "新增长任务流程", user, user, diff(null, type));
        return toResponse(type);
    }

    /** 修改类型：不存在时报错，编辑时排除自身后检查名称和排序唯一性。 */
    @Transactional
    public LongTaskTypeResponse updateType(Long id, LongTaskTypeRequest request, String user) {
        var old = mapper.selectTypeById(id);
        if (old == null) throw new IllegalArgumentException("LongTaskTypeNotFound");
        String name = normalizeName(request.name());
        int sort = validSort(request.sortOrder());
        ensureUniqueSort(id, sort);
        var same = mapper.selectTypeByName(name);
        if (same != null && !id.equals(same.getId())) throw new IllegalArgumentException("LongTaskTypeNameAlreadyExists");
        var type = new LongTaskType(id, name, request.description(), sort,
                request.enabled() == null ? old.getEnabled() : request.enabled(), old.getCreatedAt(), null);
        mapper.updateType(type);
        var changes = diff(old, type);
        if (!changes.isEmpty()) {
            auditService.record("long_task_flow", String.valueOf(id), type.getName(), "update",
                    "编辑长任务流程", user, user, changes);
        }
        return toResponse(type);
    }

    /** 删除类型前检查是否仍被未删除的流程引用。 */
    @Transactional
    public void deleteType(Long id, String user) {
        if (mapper.countFlowsByTypeId(id) > 0) throw new IllegalStateException("LongTaskTypeInUse");
        var old = mapper.selectTypeById(id);
        if (mapper.deleteType(id) == 0) throw new IllegalArgumentException("LongTaskTypeNotFound");
        auditService.record("long_task_flow", String.valueOf(id), old == null ? "" : old.getName(), "delete",
                "删除长任务流程", user, user, diff(old, null));
    }

    /** 同一排序值只能使用一次；修改时忽略当前记录。 */
    private void ensureUniqueSort(Long currentId, int sort) {
        if (mapper.selectTypes(null).stream().anyMatch(t -> sort == t.getSortOrder()
                && (currentId == null || !currentId.equals(t.getId())))) {
            throw new IllegalArgumentException("LongTaskTypeSortOrderAlreadyExists");
        }
    }

    /** 去除名称首尾空格并拒绝空名称。 */
    private static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("LongTaskTypeNameRequired");
        return name.trim();
    }

    /** 排序值必须存在且不能为负数。 */
    private static int validSort(Integer sort) {
        if (sort == null || sort < 0) throw new IllegalArgumentException("LongTaskTypeSortOrderInvalid");
        return sort;
    }

    private static List<ResourceChangeAuditService.Change> diff(LongTaskType old, LongTaskType current) {
        List<ResourceChangeAuditService.Change> changes = new java.util.ArrayList<>();
        addChange(changes, "name", "流程名称", old == null ? null : old.getName(), current == null ? null : current.getName());
        addChange(changes, "description", "流程描述", old == null ? null : old.getDescription(), current == null ? null : current.getDescription());
        addChange(changes, "sort_order", "排序值", old == null ? null : old.getSortOrder(), current == null ? null : current.getSortOrder());
        addChange(changes, "enabled", "启用状态", old == null ? null : old.getEnabled(), current == null ? null : current.getEnabled());
        return changes;
    }

    private static void addChange(List<ResourceChangeAuditService.Change> changes, String field, String label, Object before, Object after) {
        String oldValue = before == null ? null : String.valueOf(before);
        String newValue = after == null ? null : String.valueOf(after);
        if (java.util.Objects.equals(oldValue, newValue)) return;
        String type = oldValue == null ? "create" : newValue == null ? "delete" : "update";
        changes.add(new ResourceChangeAuditService.Change("resource", "", "", field, label, type,
                oldValue == null ? "（初始）" : oldValue, newValue == null ? "已删除" : newValue));
    }

    /** 将数据库实体转换成前端响应 DTO。 */
    private LongTaskTypeResponse toResponse(LongTaskType type) {
        return new LongTaskTypeResponse(type.getId(), type.getName(), type.getDescription(),
                type.getSortOrder(), type.getEnabled(), type.getCreatedAt(), type.getUpdatedAt());
    }
}




