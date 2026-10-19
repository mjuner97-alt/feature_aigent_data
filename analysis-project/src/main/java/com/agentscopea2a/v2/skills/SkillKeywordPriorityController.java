package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 全局关键词优先级管理接口（docs/202610/skill关键词优先级行模型存储方案.md v3）。
 *
 * <ul>
 *   <li>GET /v2/skill/keyword-priority        - 全量行（行序有序）</li>
 *   <li>PUT /v2/skill/keyword-priority        - 整表替换保存（row_order 服务端按
 *       列表位次生成）</li>
 *   <li>POST /v2/skill/keyword-priority/reload - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>写操作仅管理员。一行 = L1 | L2 | L3 词集（顿号分隔、格内词彼此等价，支持
 * 正则词形）；L1 无优先级（仅分组/入围身份），行序即该行 L2/L3 词的优先级
 * （小者优先）。
 */
@RestController
@RequestMapping("/v2/skill/keyword-priority")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillKeywordPriorityController {

    private final SkillKeywordPriorityRepository repository;
    private final AdminRoleService adminRoleService;

    public SkillKeywordPriorityController(SkillKeywordPriorityRepository repository,
                                          AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public List<SkillKeywordPriorityLine> list() {
        return repository.findAllLines();
    }

    @PutMapping
    public ResponseEntity<List<SkillKeywordPriorityLine>> replaceAll(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody List<SkillKeywordPriorityLine> lines) {
        requireAdmin(userId);
        validate(lines);
        repository.replaceAll(lines);
        return ResponseEntity.ok(repository.findAllLines());
    }

    @PostMapping("/reload")
    public ResponseEntity<Void> reload(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireAdmin(userId);
        repository.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    private static void validate(List<SkillKeywordPriorityLine> lines) {
        for (SkillKeywordPriorityLine line : lines == null ? List.<SkillKeywordPriorityLine>of() : lines) {
            if (line == null || SkillKeywordPriorityRepository.splitWords(line.level1Keywords()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "一级关键词不能为空（顿号分隔，至少一个词）");
            }
            if (SkillKeywordPriorityRepository.splitWords(line.level2Keywords()).isEmpty()
                    && SkillKeywordPriorityRepository.splitWords(line.level3Keywords()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "存在空白行：二级/三级至少填一格");
            }
        }
    }

    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑关键词优先级");
        }
    }
}
