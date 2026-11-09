package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import com.agentscopea2a.v2.dimension.DimensionFamilyConfig;
import com.agentscopea2a.v2.dimension.DimensionFamilyConfigRepository;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 维度家族映射管理接口（同级维度消解方案 §3.2，2026/11/06）。
 *
 * <ul>
 *   <li>GET    /v2/dimension/family          - 全量（含未启用行，管理页用）</li>
 *   <li>POST   /v2/dimension/family          - 新增</li>
 *   <li>PUT    /v2/dimension/family/{id}     - 更新</li>
 *   <li>DELETE /v2/dimension/family/{id}     - 软删（enabled=false）</li>
 *   <li>POST   /v2/dimension/family/reload   - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>family 限定代码闭集（{@link DimensionFamilyConfigRepository#ALLOWED_FAMILIES}，
 * 对应 extraction 字段）。写操作仅管理员（同维度键字典口径）。
 */
@RestController
@RequestMapping("/v2/dimension/family")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DimensionFamilyController {

    private final DimensionFamilyConfigRepository repository;
    private final AdminRoleService adminRoleService;

    public DimensionFamilyController(DimensionFamilyConfigRepository repository,
                                     AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public List<DimensionFamilyConfig> list() {
        return repository.findAllForAdmin();
    }

    @PostMapping
    public ResponseEntity<DimensionFamilyConfig> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody DimensionFamilyRequest body) {
        requireAdmin(userId);
        DimensionFamilyConfig row = parseAndValidate(body, null);
        if (!repository.insert(row)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "新增失败：词表值 " + row.value() + " 可能已存在");
        }
        return ResponseEntity.ok(row);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DimensionFamilyConfig> update(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody DimensionFamilyRequest body) {
        requireAdmin(userId);
        repository.findAllForAdmin().stream()
                .filter(r -> r.id() != null && r.id() == id)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "id 不存在"));
        DimensionFamilyConfig row = parseAndValidate(body, id);
        if (!repository.update(row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "更新失败");
        }
        return ResponseEntity.ok(row);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireAdmin(userId);
        if (!repository.setEnabled(id, false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "id 不存在");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reload")
    public ResponseEntity<Void> reload(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireAdmin(userId);
        repository.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    /** value 必填唯一；family 必须落在代码闭集内（逗号分隔，可多值），去重保序。 */
    private DimensionFamilyConfig parseAndValidate(DimensionFamilyRequest body, Long id) {
        if (body == null || isBlank(body.getValue()) || isBlank(body.getFamily())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value / family 不能为空");
        }
        Set<String> families = new LinkedHashSet<>(Arrays.asList(body.getFamily().split(",")));
        families.removeIf(String::isBlank);
        for (String f : families) {
            if (!DimensionFamilyConfigRepository.ALLOWED_FAMILIES.contains(f.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "family 必须是固定集之一（"
                                + String.join("/", DimensionFamilyConfigRepository.ALLOWED_FAMILIES)
                                + "），非法值: " + f);
            }
        }
        String family = String.join(",", families.stream().map(String::trim).toList());
        return new DimensionFamilyConfig(id, body.getValue(), family,
                body.getEnabled() == null || body.getEnabled(), body.getRemark());
    }

    /** 家族映射是全局口径配置, 写操作仅管理员 */
    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑维度家族映射");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 新增/更新请求体。enabled 缺省 true。 */
    @Data
    public static class DimensionFamilyRequest {
        private String value;
        private String family;
        private Boolean enabled;
        private String remark;
    }
}
