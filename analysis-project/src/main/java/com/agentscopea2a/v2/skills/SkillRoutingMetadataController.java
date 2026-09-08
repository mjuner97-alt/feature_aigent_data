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

/** Management API for Skill routing metadata; Skill content is intentionally out of scope. */
@RestController
@RequestMapping("/api/skill-routing")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillRoutingMetadataController {
    private final SkillRoutingMetadataAdminService service;

    public SkillRoutingMetadataController(SkillRoutingMetadataAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<SkillRoutingMetadataView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return service.list(keyword, active, mine, userId, limit, offset);
    }

    @GetMapping("/{skillName}")
    public SkillRoutingMetadataView get(@PathVariable String skillName) {
        return service.get(skillName);
    }

    @PutMapping("/{skillName}")
    public SkillRoutingMetadata save(@PathVariable String skillName, @RequestBody SkillRoutingMetadataInput input,
                                     @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return service.save(skillName, input, userId);
    }

    @PatchMapping("/{skillName}/active")
    public SkillRoutingMetadataView setActive(@PathVariable String skillName, @RequestBody ActiveRequest request) {
        return service.setActive(skillName, request != null && request.active());
    }

    public record ActiveRequest(boolean active) {}
}
