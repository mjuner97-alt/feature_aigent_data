package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import com.agentscopea2a.v2.dimension.DimensionKeyConfig;
import com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository;
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

import java.util.List;
import java.util.regex.Pattern;

/**
 * 维度键字典管理接口（docs/history/skill-direct-exec-plan.md §9.3）。
 *
 * <ul>
 *   <li>GET    /v2/dimension/key          - 全量（含未启用行，管理页用）</li>
 *   <li>POST   /v2/dimension/key          - 新增（非内置键）</li>
 *   <li>PUT    /v2/dimension/key/{id}     - 更新（内置键只读，仅允许改词表/启用态）</li>
 *   <li>DELETE /v2/dimension/key/{id}     - 软删（enabled=false，内置键拒绝）</li>
 *   <li>POST   /v2/dimension/key/reload   - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>写操作仅管理员（同维度同义词口径，2026/09/21 拍板）。
 */
@RestController
@RequestMapping("/v2/dimension/key")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DimensionKeyController {

    /** 维度键命名：camelCase 标识符，字母开头，≤64 字符。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,63}");

    private final DimensionKeyConfigRepository repository;
    private final AdminRoleService adminRoleService;

    public DimensionKeyController(DimensionKeyConfigRepository repository, AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public List<DimensionKeyConfig> list() {
        return repository.findAllForAdmin();
    }

    @PostMapping
    public ResponseEntity<DimensionKeyConfig> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody DimensionKeyRequest body) {
        requireAdmin(userId);
        if (body == null || isBlank(body.getKey()) || isBlank(body.getLabel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key / label 不能为空");
        }
        if (!KEY_PATTERN.matcher(body.getKey().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key 必须是字母开头的 camelCase 标识符（≤64 字符），如 qGate");
        }
        DimensionKeyConfig row = new DimensionKeyConfig(null, body.getKey(), body.getLabel(),
                false, aliasesOf(body), body.getEnabled() == null || body.getEnabled(), body.getRemark());
        validateAnchorsIfNeeded(row);
        if (!repository.insert(row)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "新增失败：维度键 " + row.key() + " 可能已存在");
        }
        return ResponseEntity.ok(row);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DimensionKeyConfig> update(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody DimensionKeyRequest body) {
        requireAdmin(userId);
        DimensionKeyConfig existing = repository.findAllForAdmin().stream()
                .filter(r -> r.id() != null && r.id() == id)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "id 不存在"));
        // 内置键只读 key/builtin/label 展示语义不变; label 仍可改（纯展示）
        if (existing.builtin() && body.getKey() != null && !body.getKey().trim().equals(existing.key())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内置维度键不允许修改 key");
        }
        String key = existing.builtin() ? existing.key()
                : (isBlank(body.getKey()) ? existing.key() : body.getKey().trim());
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key 必须是字母开头的 camelCase 标识符（≤64 字符）");
        }
        DimensionKeyConfig row = new DimensionKeyConfig(id, key,
                isBlank(body.getLabel()) ? existing.label() : body.getLabel(),
                existing.builtin(), aliasesOf(body),
                body.getEnabled() == null ? existing.enabled() : body.getEnabled(),
                body.getRemark() == null ? existing.remark() : body.getRemark());
        validateAnchorsIfNeeded(row);
        if (!repository.update(row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "更新失败（内置键不支持整体更新）");
        }
        return ResponseEntity.ok(row);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireAdmin(userId);
        boolean builtin = repository.findAllForAdmin().stream()
                .anyMatch(r -> r.id() != null && r.id() == id && r.builtin());
        if (builtin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内置维度键不允许删除");
        }
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

    private static List<DimensionKeyConfig.AliasMapping> aliasesOf(DimensionKeyRequest body) {
        if (body.getAliases() == null) {
            return List.of();
        }
        return body.getAliases().stream()
                .filter(a -> a != null && a.valid())
                .toList();
    }

    /** timeRange 保留键的词表值必须是锚点白名单内的语义锚点（LAST:{n}V/Q 不属于日期锚点，
     *  由 versionPlan/vestQuarter 键承接），否则执行期 TimeRangeResolver 解析炸雷。
     *  口语值方案 §3.6: 白名单内置键（versionPlan/vestQuarter/deptName）词表 value 按键分型校验。
     *  其他键不受影响。 */
    private static void validateAnchorsIfNeeded(DimensionKeyConfig row) {
        String key = row.key();
        // 正则捕获模板行 ($1~$9): 所有键通用——alias 必须可编译、引用组号不越界
        for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
            if (!alias.valid()
                    || !com.agentscopea2a.v2.dimension.AliasRegexSupport.isRegexTemplate(alias.value())) {
                continue;
            }
            String error = com.agentscopea2a.v2.dimension.AliasRegexSupport
                    .validateRegexRow(alias.alias(), alias.value());
            if (error != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "维度键 " + key + " 词表行非法（值含 $n 时 alias 按正则匹配）: " + error
                                + "，alias: " + alias.alias());
            }
        }
        if (com.agentscopea2a.v2.dimension.TimeRangeResolver.RESERVED_KEY.equals(key)) {
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (!alias.valid()) {
                    continue;
                }
                if (!com.agentscopea2a.v2.dimension.TimeRangeResolver.isValidConfigAnchor(alias.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "维度键 timeRange 的词表值必须是锚点白名单内的语义锚点"
                                    + "（THIS_WEEK / LAST_WEEK / LAST:{n}d / THIS_MONTH / THIS_YEAR），"
                                    + "非法值: " + alias.value());
                }
            }
            return;
        }
        if (com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository
                .VOCAB_EDITABLE_BUILTIN_KEYS.contains(key)) {
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (!alias.valid()) {
                    continue;
                }
                if (!com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver
                        .isValidConfigValue(key, alias.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            describeValueRule(key) + "，非法值: " + alias.value());
                }
            }
        }
    }

    private static String describeValueRule(String key) {
        return switch (key) {
            case "versionPlan" -> "维度键 versionPlan 的词表值必须是版本标准值（如 2026年10月份版本 / "
                    + "2026年10月延后版）或版本锚点（THIS_V / YEAR_V / HALF_V:1|2 / LAST:{n}V / DELAYED）";
            case "vestQuarter" -> "维度键 vestQuarter 的词表值必须是季度标准值（如 2026年4季度）或 THIS_Q";
            case "deptName" -> "维度键 deptName 的词表值必须是部门标准名（杭州开发一~五部 / "
                    + "杭州服务支持部 / 杭州技术部 / 云计算实验室）";
            default -> "维度键 " + key + " 的词表值非法";
        };
    }

    /** 维度键字典是全局口径配置, 写操作仅管理员 */
    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑维度键字典");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 新增/更新请求体。enabled 缺省 true。 */
    @Data
    public static class DimensionKeyRequest {
        private String key;
        private String label;
        private List<DimensionKeyConfig.AliasMapping> aliases;
        private Boolean enabled;
        private String remark;
    }
}
