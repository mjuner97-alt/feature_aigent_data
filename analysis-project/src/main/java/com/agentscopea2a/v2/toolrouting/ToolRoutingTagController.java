package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Management API for the shared Skill/Tool routing vocabulary. */
@RestController
@RequestMapping("/api/tool-routing/tags")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ToolRoutingTagController {

    private final ToolRoutingTagDictionary dictionary;
    private final AdminRoleService adminRoleService;

    public ToolRoutingTagController(ToolRoutingTagDictionary dictionary, AdminRoleService adminRoleService) {
        this.dictionary = dictionary;
        this.adminRoleService = adminRoleService;
    }

    @GetMapping("/{tagType}")
    public List<ToolRoutingTag> listEnabled(@PathVariable ToolRoutingTagType tagType) {
        return dictionary.findEnabled(tagType);
    }

    @PutMapping("/{tagType}/{tagName}")
    public ToolRoutingTag save(@PathVariable ToolRoutingTagType tagType, @PathVariable String tagName,
                               @RequestBody ToolRoutingTagInput input,
                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!adminRoleService.isAdminUserId(userId)) {
            throw new IllegalStateException("ResourceAccessDenied");
        }
        ToolRoutingTag tag = new ToolRoutingTag(tagType, tagName, input.description(), input.enabled());
        if (!dictionary.upsert(tag)) {
            throw new IllegalStateException("TagUpsertFailed: " + tagName);
        }
        return tag;
    }

    public record ToolRoutingTagInput(String description, boolean enabled) {
    }
}
