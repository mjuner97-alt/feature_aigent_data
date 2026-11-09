package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import com.agentscopea2a.v2.dimension.DimensionSignalConfigRepository;
import com.agentscopea2a.v2.skills.DirectSkillExecutor;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 维度信号动态键白名单管理接口（C3 追问信号白名单，2026/11/09 前端化）。
 *
 * <ul>
 *   <li>GET  /v2/dimension/signal-keys - 当前白名单（keys 列表 + 生效来源）</li>
 *   <li>POST /v2/dimension/signal-keys - 保存（仅管理员），落库 + 运行时立即生效</li>
 * </ul>
 *
 * <p>对应配置项 harness.a2a.skill-direct-exec.followup-signal-keys（逗号分隔动态键，
 * 默认 drillDown,dimension,timeRange）。保存后调 {@link DirectSkillExecutor#setFollowupSignalKeys(String)}
 * 运行时生效并跨重启持久化（dimension_signal_config 表）。
 */
@RestController
@RequestMapping("/v2/dimension/signal-keys")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DimensionSignalKeysController {

    /** 动态键形态约束：合法参数键（不含逗号/空白，避免破坏 CSV 存储）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final DimensionSignalConfigRepository repository;
    private final DirectSkillExecutor directSkillExecutor;
    private final AdminRoleService adminRoleService;

    public DimensionSignalKeysController(DimensionSignalConfigRepository repository,
                                         DirectSkillExecutor directSkillExecutor,
                                         AdminRoleService adminRoleService) {
        this.repository = repository;
        this.directSkillExecutor = directSkillExecutor;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public SignalKeysResponse get() {
        SignalKeysResponse resp = new SignalKeysResponse();
        String csv = repository.loadFollowupSignalKeys();
        if (csv == null) {
            csv = DimensionSignalConfigRepository.DEFAULT_FOLLOWUP_SIGNAL_KEYS;
            resp.setSource("default");
        } else {
            resp.setSource("database");
        }
        resp.setKeys(parseCsv(csv));
        return resp;
    }

    @PostMapping
    public ResponseEntity<SignalKeysResponse> save(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SignalKeysRequest body) {
        requireAdmin(userId);
        Set<String> keys = parseKeys(body);
        String csv = String.join(",", keys);
        if (!repository.saveFollowupSignalKeys(csv)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存失败（写库异常）");
        }
        directSkillExecutor.setFollowupSignalKeys(csv);
        SignalKeysResponse resp = new SignalKeysResponse();
        resp.setKeys(List.copyOf(keys));
        resp.setSource("database");
        return ResponseEntity.ok(resp);
    }

    /** 非空 + 去重保序 + 键形态校验（标识符，禁逗号/空白）。 */
    private Set<String> parseKeys(SignalKeysRequest body) {
        if (body == null || body.getKeys() == null || body.getKeys().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "至少保留一个信号键（默认 drillDown/dimension/timeRange）");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String raw : body.getKeys()) {
            if (raw == null) continue;
            String key = raw.trim();
            if (key.isEmpty()) continue;
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "非法信号键: " + key + "（仅允许字母开头的字母/数字/下划线）");
            }
            keys.add(key);
        }
        if (keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少保留一个信号键");
        }
        return keys;
    }

    private List<String> parseCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑维度信号键白名单");
        }
    }

    @Data
    public static class SignalKeysRequest {
        private List<String> keys;
    }

    @Data
    public static class SignalKeysResponse {
        private List<String> keys;
        /** database=来自 dimension_signal_config 表; default=内置默认 */
        private String source;
    }
}
