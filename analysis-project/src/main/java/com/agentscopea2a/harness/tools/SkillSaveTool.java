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

import com.agentscopea2a.harness.skills.SkillIndexRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for saving generated skills as SKILL.md files to the local file system.
 *
 * <p>PR1 — Skill metadata baseline. On every save we:
 * <ol>
 *   <li>Upsert {@code skill_index} (atomic version bump via {@code ON DUPLICATE KEY UPDATE})
 *   <li>Render a managed YAML frontmatter (name / description / version / last_evolved_at)
 *   <li>Strip any LLM-supplied frontmatter to prevent drift between file and DB
 *   <li>Overwrite SKILL.md with frontmatter + body
 * </ol>
 *
 * <p>Used by the SkillGeneratorAgent to persist generated skill definitions.
 */
public class SkillSaveTool {

    private static final Logger log = LoggerFactory.getLogger(SkillSaveTool.class);

    /** Matches a YAML frontmatter block at the very start of the content. */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\R(?:.*?\\R)*?---\\s*\\R?", Pattern.DOTALL);

    private final Path skillsDir;
    private final SkillIndexRepository indexRepository;

    /** Repository may be {@code null} when wired in legacy single-arg paths (tests, etc.). */
    public SkillSaveTool(Path skillsDir, SkillIndexRepository indexRepository) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
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
                            + "content是SKILL.md的正文部分（不含YAML frontmatter，"
                            + "系统会自动生成 name/description/version/last_evolved_at）。")
    public ToolResultBlock saveSkill(
            @ToolParam(
                            name = "skill_name",
                            description = "技能名称，使用英文小写+下划线（如 quality_query_analysis）")
                    String skillName,
            @ToolParam(name = "description", description = "技能的一句话中文描述") String description,
            @ToolParam(name = "content", description = "SKILL.md的完整正文内容（不含YAML frontmatter）")
                    String content) {
        try {
            if (skillName == null || skillName.isBlank()) {
                return ToolResultBlock.error("skill_name 不能为空");
            }
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            String desc = description == null ? "" : description.trim();
            String body = stripFrontmatter(content == null ? "" : content.trim());

            int version = upsertVersion(safeName, desc);
            String frontmatter = renderFrontmatter(safeName, desc, version);
            String full = frontmatter + body;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(safeName)
                            .description(desc)
                            .skillContent(full)
                            .source("user_generated")
                            .build();

            boolean saved = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
            if (saved) {
                String msg =
                        "技能保存成功 v"
                                + version
                                + " — "
                                + skillsDir.resolve(safeName).resolve("SKILL.md");
                log.info("Skill saved: {} v{}", safeName, version);
                return ToolResultBlock.text(msg);
            }
            return ToolResultBlock.error("技能保存失败，请重试");
        } catch (Exception e) {
            log.error("Failed to save skill: {}", skillName, e);
            return ToolResultBlock.error("保存技能时出错: " + e.getMessage());
        }
    }

    private int upsertVersion(String name, String description) {
        if (indexRepository == null) {
            return 1;
        }
        int v = indexRepository.upsertOnSave(name, description);
        return v > 0 ? v : 1;
    }

    /** Drop any YAML frontmatter the LLM may have prepended — we own this block. */
    static String stripFrontmatter(String content) {
        if (content == null || content.isEmpty()) return "";
        Matcher m = FRONTMATTER.matcher(content);
        return m.find() && m.start() == 0 ? content.substring(m.end()) : content;
    }

    static String renderFrontmatter(String name, String description, int version) {
        // Escape only what YAML genuinely needs: double-quote the description and backslash
        // any literal " inside it. Names are already [a-z0-9_] from safeName().
        String safeDesc = description.replace("\\", "\\\\").replace("\"", "\\\"");
        return "---\n"
                + "name: "
                + name
                + "\n"
                + "description: \""
                + safeDesc
                + "\"\n"
                + "version: "
                + version
                + "\n"
                + "last_evolved_at: "
                + LocalDate.now()
                + "\n"
                + "---\n\n";
    }
}
