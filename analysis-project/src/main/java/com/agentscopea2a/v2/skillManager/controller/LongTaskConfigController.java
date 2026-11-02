package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.LongTaskTypePageResponse;
import com.agentscopea2a.v2.skillManager.dto.LongTaskTypeRequest;
import com.agentscopea2a.v2.skillManager.dto.LongTaskTypeResponse;
import com.agentscopea2a.v2.skillManager.service.LongTaskConfigService;
import org.springframework.web.bind.annotation.*;

/** 长任务流程类型管理接口：提供类型列表、创建、修改和删除能力。 */
@RestController
@RequestMapping("/api/long-task-types")
public class LongTaskConfigController {
    private final LongTaskConfigService service;

    /** 注入业务层，控制器只负责接收参数和返回结果。 */
    public LongTaskConfigController(LongTaskConfigService service) { this.service = service; }

    /** 分页查询流程类型，可按启用状态筛选。 */
    @GetMapping
    public LongTaskTypePageResponse list(@RequestParam(required = false) Boolean enabled,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        return service.listTypesPage(enabled, page, pageSize);
    }

    /** 创建流程类型；用户编号用于记录操作人和审计信息。 */
    @PostMapping
    public LongTaskTypeResponse create(@RequestBody LongTaskTypeRequest request,
                                       @RequestHeader("X-User-Id") String user) {
        return service.createType(request, user);
    }

    /** 修改指定流程类型的名称、描述、排序和启用状态。 */
    @PutMapping("/{id}")
    public LongTaskTypeResponse update(@PathVariable(name = "id") Long id, @RequestBody LongTaskTypeRequest request,
                                       @RequestHeader("X-User-Id") String user) {
        return service.updateType(id, request, user);
    }

    /** 删除流程类型；若仍被流程引用，业务层会拒绝删除。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable(name = "id") Long id, @RequestHeader("X-User-Id") String user) {
        service.deleteType(id, user);
    }
}




