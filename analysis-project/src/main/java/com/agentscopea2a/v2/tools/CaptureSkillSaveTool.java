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
package com.agentscopea2a.v2.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capture-only variant of {@link SkillSaveTool} for use by the distillation subagent.
 *
 * <p>Instead of writing to disk, this tool records the {@code save_skill} call parameters
 * (skill name, description, content) in memory so that {@code SkillSynthesisRunner} can
 * perform the CAS check, embedding, and disk write in a single transaction after the
 * subagent finishes.
 *
 * <p>The tool returns a success message identical to the real {@code SkillSaveTool} so
 * the LLM naturally concludes its generation loop.
 *
 * @see com.agentscopea2a.v2.tools.SkillSaveTool
 */
public class CaptureSkillSaveTool {

    private static final Logger log = LoggerFactory.getLogger(CaptureSkillSaveTool.class);

    private volatile CapturedSkill captured;

    /** Immutable record of the parameters the LLM passed to {@code save_skill}. */
    public record CapturedSkill(String name, String description, String content) {}

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
            @ToolParam(
                            name = "content",
                            description = "SKILL.md的完整正文内容（不含YAML frontmatter）")
                    String content) {
        log.info("CaptureSkillSaveTool.saveSkill() called: name={}, descLength={}, contentLength={}",
                skillName, description == null ? 0 : description.length(), content == null ? 0 : content.length());
        this.captured = new CapturedSkill(skillName, description, content);
        return ToolResultBlock.text("Skill '" + skillName + "' saved successfully.");
    }

    /** Returns the captured skill, or {@code null} if the LLM never called save_skill. */
    public CapturedSkill getCaptured() {
        return captured;
    }

    /** Whether the LLM has called save_skill at least once. */
    public boolean hasCaptured() {
        return captured != null;
    }
}