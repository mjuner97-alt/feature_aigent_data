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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全局关键词优先级管理接口（docs/history/skill-retrieval-logic.md §8.1 拍板⑧）。
 *
 * <ul>
 *   <li>GET /v2/skill/keyword-priority        - 全量（level, sort_order 有序）</li>
 *   <li>PUT /v2/skill/keyword-priority        - 整表替换保存（拖曳页全量树提交，
 *       sort_order 服务端按列表位次生成）</li>
 *   <li>POST /v2/skill/keyword-priority/reload - 立即失效 TTL 快照</li>
 * </ul>
 *
 * <p>写操作仅管理员。校验：level ∈ {1,2,3}；keyword 必填（支持 、，, 分割，保存时拆成多行）；
 * parent_keyword 存配置行号（R1/R2…，仅用于前端还原行结构，不参与匹配/排序）；同级同行同词去重。
 */
@RestController
@RequestMapping("/v2/skill/keyword-priority")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillKeywordPriorityController {

    private static final Pattern SEPARATORS = Pattern.compile("[、，,]");

    private final SkillKeywordPriorityRepository repository;
    private final AdminRoleService adminRoleService;

    public SkillKeywordPriorityController(SkillKeywordPriorityRepository repository,
                                          AdminRoleService adminRoleService) {
        this.repository = repository;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public List<SkillKeywordPriority> list() {
        return repository.findAllForAdmin();
    }

    @PutMapping
    public ResponseEntity<List<SkillKeywordPriority>> replaceAll(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody List<SkillKeywordPriority> rows) {
        requireAdmin(userId);
        List<SkillKeywordPriority> normalized = validate(rows);
        repository.replaceAll(normalized);
        return ResponseEntity.ok(repository.findAllForAdmin());
    }

    @PostMapping("/reload")
    public ResponseEntity<Void> reload(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireAdmin(userId);
        repository.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    /**
     * 校验 + 分隔符展开：keyword 按 、，, 拆成多行；parent_keyword 存行号（R1/R2…，
     * 仅用于前端还原配置行，不参与匹配/排序），level≥1 均必填；同级同行同词去重。
     */
    private static List<SkillKeywordPriority> validate(List<SkillKeywordPriority> rows) {
        if (rows == null) {
            rows = List.of();
        }
        List<SkillKeywordPriority> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int lastLevel = 0;
        for (SkillKeywordPriority row : rows) {
            if (row == null || !row.valid()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "行非法：keyword 不能为空、level 必须 1~3、parent（行号）必填");
            }
            if (row.level() < lastLevel) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "行必须按 level 升序提交（一级在前，其次二级、三级）");
            }
            lastLevel = row.level();
            List<String> keywords = splitKeywords(row.keyword());
            if (keywords.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行非法：keyword 不能为空");
            }
            for (String keyword : keywords) {
                String parent = row.parentKeyword() == null ? "" : row.parentKeyword().trim();
                String dedupKey = row.level() + "|" + keyword + "|" + parent;
                if (!seen.add(dedupKey)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "重复行: level=" + row.level() + ", keyword=" + keyword + ", parent=" + parent);
                }
                normalized.add(new SkillKeywordPriority(null, row.level(), parent, keyword, row.sortOrder()));
            }
        }
        return normalized;
    }

    /** 按 、，, 拆词、去空白片段；全是分隔符时返回空列表（上层报行非法）。 */
    private static List<String> splitKeywords(String value) {
        List<String> pieces = new ArrayList<>();
        for (String piece : SEPARATORS.split(value)) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                pieces.add(trimmed);
            }
        }
        return pieces;
    }

    private void requireAdmin(String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可编辑关键词优先级");
        }
    }
}
