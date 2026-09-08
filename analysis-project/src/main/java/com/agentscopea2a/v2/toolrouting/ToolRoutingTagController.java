package com.agentscopea2a.v2.toolrouting;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Management API for the shared Skill/Tool routing vocabulary. */
@RestController
@RequestMapping("/api/tool-routing/tags")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ToolRoutingTagController {

    private final ToolRoutingTagDictionary dictionary;

    public ToolRoutingTagController(ToolRoutingTagDictionary dictionary) {
        this.dictionary = dictionary;
    }

    @GetMapping("/{tagType}")
    public List<ToolRoutingTag> listEnabled(@PathVariable ToolRoutingTagType tagType) {
        return dictionary.findEnabled(tagType);
    }

    @PutMapping("/{tagType}/{tagName}")
    public ToolRoutingTag save(@PathVariable ToolRoutingTagType tagType, @PathVariable String tagName,
                               @RequestBody ToolRoutingTagInput input) {
        ToolRoutingTag tag = new ToolRoutingTag(tagType, tagName,
                input == null || input.description() == null ? "" : input.description(),
                input == null || input.enabled());
        if (!dictionary.upsert(tag)) {
            throw new IllegalStateException("ToolRoutingTagSaveFailed");
        }
        return tag;
    }

    public record ToolRoutingTagInput(String description, boolean enabled) {
    }
}
