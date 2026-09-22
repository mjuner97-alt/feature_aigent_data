/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.dimension.DimensionAlias;
import com.agentscopea2a.v2.dimension.DimensionAliasRepository;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/**
 * 维度同义词管理接口（docs/dimension-alias-config-plan.md §7，Phase 1 仅后端）。
 *
 * <ul>
 *   <li>GET    /v2/dimension/alias/{dimension} - 列表（含未启用行）</li>
 *   <li>POST   /v2/dimension/alias/{dimension} - 新增</li>
 *   <li>PUT    /v2/dimension/alias/{id}        - 更新</li>
 *   <li>DELETE /v2/dimension/alias/{id}        - 软删（enabled=false）</li>
 *   <li>POST   /v2/dimension/alias/reload      - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>{@code dimension} ∈ team / product-line / application（大小写不敏感）。
 */
@RestController
@RequestMapping("/v2/dimension/alias")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DimensionAliasController {

    private final DimensionAliasRepository repository;
    private final com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService;

    public DimensionAliasController(DimensionAliasRepository repository,
                                    com.agentscopea2a.v2.auth.service.AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping("/{dimension}")
    public List<DimensionAlias> list(@PathVariable("dimension") String dimension) {
        return repository.findAllForAdmin().stream()
                .filter(r -> r.dimension() == parseDimension(dimension))
                .toList();
    }

    @PostMapping("/{dimension}")
    public ResponseEntity<DimensionAlias> create(
            @PathVariable("dimension") String dimension,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody AliasRequest body) {
        requireAdmin(userId);
        if (body == null || isBlank(body.getAlias()) || isBlank(body.getStandardName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alias / standardName 不能为空");
        }
        DimensionAlias row = new DimensionAlias(null, parseDimension(dimension),
                body.getAlias(), body.getStandardName(), body.getTriggerKeyword(),
                body.getEnabled() == null || body.getEnabled(), body.getRemark());
        if (!repository.insert(row)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "新增失败：可能违反 (dimension, alias, standard_name) 唯一约束");
        }
        return ResponseEntity.ok(row);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DimensionAlias> update(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody AliasRequest body) {
        requireAdmin(userId);
        if (body == null || body.getDimension() == null
                || isBlank(body.getAlias()) || isBlank(body.getStandardName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dimension / alias / standardName 不能为空");
        }
        DimensionAlias row = new DimensionAlias(id, body.getDimension(),
                body.getAlias(), body.getStandardName(), body.getTriggerKeyword(),
                body.getEnabled() == null || body.getEnabled(), body.getRemark());
        if (!repository.update(row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "id 不存在或更新失败");
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

    /** 维度同义词是全局口径配置, 写操作仅管理员（2026/09/21 拍板） */
    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑维度同义词");
        }
    }

    private static PeerDimensionType parseDimension(String raw) {
        if (raw == null) {
            throw badDimension(raw);
        }
        String v = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (v) {
            case "team", "group" -> PeerDimensionType.TEAM;
            case "product-line", "productline", "pl" -> PeerDimensionType.PRODUCT_LINE;
            case "application", "app" -> PeerDimensionType.APPLICATION;
            default -> throw badDimension(raw);
        };
    }

    private static ResponseStatusException badDimension(String raw) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "dimension 必须是 team / product-line / application，实际: " + raw);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 新增/更新请求体。enabled 缺省 true。 */
    @Data
    public static class AliasRequest {
        /** 仅 PUT 需要；POST 时 dimension 取路径参数 */
        private PeerDimensionType dimension;
        private String alias;
        private String standardName;
        private String triggerKeyword;
        private Boolean enabled;
        private String remark;
    }
}
