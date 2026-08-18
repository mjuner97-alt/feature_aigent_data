package com.agentscopea2a.v2.registry.controller;

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.v2.registry.dto.SqlTestRequest;
import com.agentscopea2a.v2.registry.dto.SqlTestResult;
import com.agentscopea2a.v2.registry.service.SqlRegistryManageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SQL 注册表管理 REST 接口.
 *
 * <p>提供 CRUD + SQL 测试功能. SQL 测试只允许 SELECT, 禁止 DDL/DML.
 * 测试时根据 params_schema 填充 :param 占位符后执行.
 */
@RestController
@RequestMapping("/api/sql-registry")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SqlRegistryController {

    private final SqlRegistryManageService service;

    public SqlRegistryController(SqlRegistryManageService service) {
        this.service = service;
    }

    // ==================== CRUD ====================

    /**
     * 列表 (含禁用记录, 可选按 datasource / createdBy 筛选).
     */
    @GetMapping
    public List<SqlRegistryEntry> list(
            @RequestParam(name = "datasource", required = false) String datasource,
            @RequestParam(name = "createdBy", required = false) String createdBy) {
        return service.list(datasource, createdBy);
    }

    /**
     * 详情 (含 sql_template).
     */
    @GetMapping("/get")
    public ResponseEntity<SqlRegistryEntry> get(@RequestParam(name = "id") Long id) {
        SqlRegistryEntry entry = service.getById(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    /**
     * 新增.
     */
    @PostMapping
    public SqlRegistryEntry create(@RequestBody SqlRegistryEntry entry,
                                   @RequestHeader("X-User-Id") String userId) {
        return service.create(entry, userId);
    }

    /**
     * 修改.
     */
    @PutMapping
    public SqlRegistryEntry update(@RequestParam(name = "id") Long id,
                                   @RequestBody SqlRegistryEntry patch,
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

    // ==================== SQL 测试 ====================

    /**
     * SQL 测试: 前端直接传入 sql_template / datasource / params_schema / params,
     * 路由到对应数据源执行. 只允许 SELECT, 禁止 DDL/DML.
     */
    @PostMapping("/test")
    public SqlTestResult test(@RequestBody SqlTestRequest request,
                              @RequestHeader("X-User-Id") String userId) {
        return service.testSql(request.getSqlTemplate(), request.getDatasource(),
                request.getParamsSchema(), request.getParams());
    }
}
