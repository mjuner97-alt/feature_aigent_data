package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.toolrouting.ToolRoutingTag;
import com.agentscopea2a.v2.toolrouting.ToolRoutingTagType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill 侧路由标签词典管理 API (领域 DOMAIN + 业务主题 TOPIC 两类)。
 * 与工具路由的 /api/tool-routing/tags 完全独立, 各自维护各自的词典表。
 */
@RestController
@RequestMapping("/api/skill-routing/tags")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillRoutingTagController {

    private final SkillRoutingTagDictionary dictionary;

    public SkillRoutingTagController(SkillRoutingTagDictionary dictionary) {
        this.dictionary = dictionary;
    }

    @GetMapping("/{tagType}")
    public List<ToolRoutingTag> listEnabled(@PathVariable ToolRoutingTagType tagType) {
        return dictionary.findEnabled(tagType);
    }

    @PutMapping("/{tagType}/{tagName}")
    public ToolRoutingTag save(@PathVariable ToolRoutingTagType tagType, @PathVariable String tagName,
                               @RequestBody ToolRoutingTagInput input) {
        throw new IllegalStateException("ResourceAccessDenied");
    }

    public record ToolRoutingTagInput(String description, boolean enabled) {
    }
}
