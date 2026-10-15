package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
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

/**
 * 检索旁路词管理接口（docs/history/skill-retrieval-logic.md §8.2，DimensionKeyController 模式）。
 *
 * <ul>
 *   <li>GET    /v2/skill/bypass-keyword        - 全量（含未启用行，管理页用）</li>
 *   <li>POST   /v2/skill/bypass-keyword        - 新增</li>
 *   <li>PUT    /v2/skill/bypass-keyword/{id}   - 更新</li>
 *   <li>DELETE /v2/skill/bypass-keyword/{id}   - 软删（enabled=false）</li>
 *   <li>POST   /v2/skill/bypass-keyword/reload - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>写操作仅管理员（同维度键字典口径）。旁路词为 selector 词条语义：可编译为正则按
 * 正则解释，编译失败回退字面 contains（拍板⑤/⑦，前端 hint 提示 lookaround 写法）。
 */
@RestController
@RequestMapping("/v2/skill/bypass-keyword")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillBypassKeywordController {

    private final SkillBypassKeywordRepository repository;
    private final AdminRoleService adminRoleService;

    public SkillBypassKeywordController(SkillBypassKeywordRepository repository,
                                        AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public List<SkillBypassKeyword> list() {
        return repository.findAllForAdmin();
    }

    @PostMapping
    public ResponseEntity<SkillBypassKeyword> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SkillBypassKeywordRequest body) {
        requireAdmin(userId);
        SkillBypassKeyword row = fromBody(body, null);
        if (!repository.insert(row)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "新增失败");
        }
        return ResponseEntity.ok(row);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillBypassKeyword> update(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SkillBypassKeywordRequest body) {
        requireAdmin(userId);
        SkillBypassKeyword row = fromBody(body, id);
        if (!repository.update(row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "id 不存在");
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

    private static SkillBypassKeyword fromBody(SkillBypassKeywordRequest body, Long id) {
        if (body == null || body.getKeyword() == null || body.getKeyword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword 不能为空");
        }
        if (body.getKeyword().trim().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword 长度不能超过 128 字符");
        }
        return new SkillBypassKeyword(id, body.getGroupName(), body.getKeyword(),
                body.getEnabled() == null || body.getEnabled(), body.getRemark());
    }

    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑检索旁路词");
        }
    }

    /** 新增/更新请求体。enabled 缺省 true。 */
    @Data
    public static class SkillBypassKeywordRequest {
        private String groupName;
        private String keyword;
        private Boolean enabled;
        private String remark;
    }
}
