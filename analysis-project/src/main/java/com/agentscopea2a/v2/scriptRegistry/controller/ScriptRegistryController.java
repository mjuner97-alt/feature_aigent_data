package com.agentscopea2a.v2.scriptRegistry.controller;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.v2.scriptRegistry.service.ScriptRegistryManageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Python 脚本注册表管理 REST 接口.
 *
 * <p>提供 CRUD (不含试运行, 脚本执行留给 agent 工具 {@code script_exec}).
 * 与 {@link com.agentscopea2a.v2.sqlRegistry.controller.SqlRegistryController} 同构.
 */
@RestController
@RequestMapping("/api/script-registry")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ScriptRegistryController {

    private final ScriptRegistryManageService service;

    public ScriptRegistryController(ScriptRegistryManageService service) {
        this.service = service;
    }

    // ==================== 全局异常处理 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // ==================== CRUD ====================

    /**
     * 列表 (含禁用记录, 可选按 datasource / createdBy 筛选).
     * datasource 对 script_registry.datasources (JSON 数组字符串) 做子串匹配.
     */
    @GetMapping
    public List<ScriptRegistryEntry> list(
            @RequestParam(name = "datasource", required = false) String datasource,
            @RequestParam(name = "createdBy", required = false) String createdBy) {
        return service.list(datasource, createdBy);
    }

    /**
     * 详情 (含 params_schema).
     */
    @GetMapping("/get")
    public ResponseEntity<ScriptRegistryEntry> get(@RequestParam(name = "id") Long id) {
        ScriptRegistryEntry entry = service.getById(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    /**
     * 新增.
     */
    @PostMapping
    public ScriptRegistryEntry create(@RequestBody ScriptRegistryEntry entry,
                                      @RequestHeader("X-User-Id") String userId) {
        return service.create(entry, userId);
    }

    /**
     * 修改.
     */
    @PutMapping
    public ScriptRegistryEntry update(@RequestParam(name = "id") Long id,
                                      @RequestBody ScriptRegistryEntry patch,
                                      @RequestHeader("X-User-Id") String userId) {
        return service.update(id, patch);
    }

    /**
     * 删除.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam(name = "id") Long id,
                      @RequestHeader("X-User-Id") String userId) {
        service.delete(id);
    }
}
