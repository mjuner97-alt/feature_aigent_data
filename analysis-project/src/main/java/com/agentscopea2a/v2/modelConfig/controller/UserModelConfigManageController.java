package com.agentscopea2a.v2.modelConfig.controller;

import com.agentscopea2a.entity.UserModelConfig;
import com.agentscopea2a.v2.modelConfig.dto.ModelTestResult;
import com.agentscopea2a.v2.modelConfig.dto.UserModelConfigListItem;
import com.agentscopea2a.v2.modelConfig.service.UserModelConfigManageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户模型配置管理 REST 接口 (内部使用, 前端不暴露入口, 仅直接访问 URL).
 *
 * <p>提供 CRUD + 连接测试. 修改 / 删除后会自动清除 {@code ModelProvider} 缓存使立即生效.
 */
@RestController
@RequestMapping("/api/model-config")
@CrossOrigin(origins = "*", maxAge = 3600)
public class UserModelConfigManageController {

    private final UserModelConfigManageService service;

    public UserModelConfigManageController(UserModelConfigManageService service) {
        this.service = service;
    }

    // ==================== CRUD ====================

    /**
     * 列表 (token 脱敏).
     */
    @GetMapping
    public List<UserModelConfigListItem> list() {
        return service.list();
    }

    /**
     * 详情 (含完整 token).
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserModelConfig> get(@PathVariable("userId") String userId) {
        UserModelConfig config = service.getByUserId(userId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /**
     * 新增.
     */
    @PostMapping
    public UserModelConfig create(@RequestBody UserModelConfig config) {
        return service.create(config);
    }

    /**
     * 修改 (选择性覆盖非 null 字段).
     */
    @PutMapping("/{userId}")
    public UserModelConfig update(@PathVariable("userId") String userId,
                                  @RequestBody UserModelConfig patch) {
        return service.update(userId, patch);
    }

    /**
     * 删除.
     */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("userId") String userId) {
        service.delete(userId);
    }

    // ==================== 连接测试 ====================

    /**
     * 连接测试: 表单当前 (可能未保存) 的 请求地址 / key / 模型.
     * 复用 {@link UserModelConfig} 作请求体, 只用 provider / requestUrl / token / modelName.
     */
    @PostMapping("/test")
    public ModelTestResult test(@RequestBody UserModelConfig config) {
        return service.testConnection(config.getProvider(), config.getRequestUrl(),
                config.getToken(), config.getModelName());
    }
}
