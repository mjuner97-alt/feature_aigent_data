package com.agentscopea2a.v2.skills;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.agentscopea2a.v2.common.PageResponse;

/** Management API for Skill routing metadata; Skill content is intentionally out of scope. */
@RestController
@RequestMapping("/api/skill-routing")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillRoutingMetadataController {
    private final SkillRoutingMetadataAdminService service;
    private final SkillDimensionCollisionService collisionService;

    public SkillRoutingMetadataController(SkillRoutingMetadataAdminService service,
                                          SkillDimensionCollisionService collisionService) {
        this.service = service;
        this.collisionService = collisionService;
    }

    @GetMapping
    public PageResponse<SkillRoutingMetadataView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        int safeSize = Math.max(1, Math.min(pageSize(limit), 100));
        int safeOffset = Math.max(0, offset);
        List<SkillRoutingMetadataView> items = service.list(keyword, active, mine, userId, safeSize, safeOffset);
        int total = service.count(keyword, active, mine, userId);
        return new PageResponse<>(items, safeOffset / safeSize + 1, safeSize, total);
    }

    private int pageSize(int requested) { return requested <= 0 ? 20 : requested; }

    @GetMapping("/{skillName}")
    public SkillRoutingMetadataView get(@PathVariable String skillName) {
        return service.get(skillName);
    }

    @PutMapping("/{skillName}")
    public SkillRoutingMetadataView save(@PathVariable String skillName, @RequestBody SkillRoutingMetadataInput input,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // 返回解析后的 View (paramMapping 为 Map): record 的 paramMapping 是原始 JSON 字符串,
        // 前端把它 spread 回行数据后 Object.entries(字符串) 会按字符下标炸开
        service.save(skillName, input, userId);
        SkillRoutingMetadataView view = service.get(skillName);
        // 撞车审计仅提示不阻断 (方案 §5): 词条 × 维度实体名 HIGH/MED, 运行期由 span 屏蔽兜底
        return view.withDimensionCollisions(collisionService.auditSkill(skillName,
                view.keywords(), view.level1Keywords(), view.level2Keywords(), view.level3Keywords()));
    }

    @PatchMapping("/{skillName}/active")
    public SkillRoutingMetadataView setActive(@PathVariable String skillName, @RequestBody ActiveRequest request,
                                              @RequestHeader("X-User-Id") String userId) {
        return service.setActive(skillName, request != null && request.active(), userId);
    }

    /** 直接执行配置独立保存: 权限=属主或管理员 (不含生产收紧), 与主配置 PUT 分离以便生产环境分权。 */
    @PutMapping("/{skillName}/direct-exec")
    public SkillRoutingMetadataView saveDirectExec(@PathVariable String skillName,
                                                   @RequestBody SkillRoutingMetadataAdminService.DirectExecInput input,
                                                   @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.saveDirectExec(skillName, input, userId);
        return service.get(skillName);
    }

    public record ActiveRequest(boolean active) {}
}
