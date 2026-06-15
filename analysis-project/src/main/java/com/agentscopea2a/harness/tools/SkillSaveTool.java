/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Tool for saving generated skills as SKILL.md files to the local file system.
 *
 * <p>Used by the SkillGeneratorAgent to persist generated skill definitions.
 */
public class SkillSaveTool {

    private static final Logger log = LoggerFactory.getLogger(SkillSaveTool.class);

    private final Path skillsDir;

    public SkillSaveTool(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * Saves a skill as a SKILL.md file under the configured skills directory.
     *
     * @param skillName skill name (lowercase English with underscores, e.g. "quality_query")
     * @param description one-line description of the skill
     * @param content the full SKILL.md body content (excluding YAML frontmatter)
     * @return tool result indicating success or failure
     */
    @Tool(
            name = "save_skill",
            description =
                    "将生成的技能内容保存为SKILL.md文件。"
                            + "skill_name使用英文小写+下划线命名，"
                            + "content是SKILL.md的正文部分（不含YAML frontmatter）。")
    public ToolResultBlock saveSkill(
            @ToolParam(
                            name = "skill_name",
                            description = "技能名称，使用英文小写+下划线（如 quality_query_analysis）")
                    String skillName,
            @ToolParam(name = "description", description = "技能的一句话中文描述") String description,
            @ToolParam(name = "content", description = "SKILL.md的完整正文内容（不含YAML frontmatter）")
                    String content) {
        try {
            // Validate skill name
            if (skillName == null || skillName.isBlank()) {
                return ToolResultBlock.error("skill_name 不能为空");
            }
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(safeName)
                            .description(description != null ? description.trim() : "")
                            .skillContent(content != null ? content.trim() : "")
                            .source("user_generated")
                            .build();

            boolean saved = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
            if (saved) {
                String msg = "技能保存成功！文件路径: " + skillsDir.resolve(safeName).resolve("SKILL.md");
                log.info("Skill saved: {}", safeName);
                return ToolResultBlock.text(msg);
            } else {
                return ToolResultBlock.error("技能保存失败，请重试");
            }
        } catch (Exception e) {
            log.error("Failed to save skill: {}", skillName, e);
            return ToolResultBlock.error("保存技能时出错: " + e.getMessage());
        }
    }
}
