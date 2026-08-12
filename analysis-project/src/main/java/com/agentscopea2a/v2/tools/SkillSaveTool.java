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

import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for saving generated skills as SKILL.md files to the local file system.
 *
 * <p>PR1 - Skill metadata baseline. On every save we:
 * <ol>
 *   <li>Upsert {@code skill_index} (atomic version bump via {@code ON DUPLICATE KEY UPDATE})
 *   <li>Render a managed YAML frontmatter (name / description / version / last_evolved_at)
 *   <li>Strip any LLM-supplied frontmatter to prevent drift between file and DB
 *   <li>Overwrite SKILL.md with frontmatter + body
 * </ol>
 *
 * <p>agent-saved skills are synced to the {@code skill_manage} table so the management UI can see
 * them. 检索名直接用 sanitized skill 名字(不再加 usr_<userId>_ 前缀);同名 skill 一律拒绝。
 *
 * <p>Used by the SkillGeneratorAgent to persist generated skill definitions.
 */
public class SkillSaveTool implements RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SkillSaveTool.class);

    /** Matches a YAML frontmatter block at the very start of the content. */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\R(?:.*?\\R)*?---\\s*\\R?", Pattern.DOTALL);

    private final Path skillsDir;
    private final SkillIndexRepository indexRepository;
    /**
     * {@code user_generated} when wired for the {@code skill_save} tool (writes to
     * {@code skills-user/}); {@code auto_synthesized} when wired for any of the auto paths
     * (W2/W3/W4/W5, writes to {@code skills-auto/}). Persisted into {@code skill_index.source}
     * on insert and never overwritten on update.
     */
    private final String source;

    /** ObjectProvider for SkillManageService to avoid circular dependency. */
    private final ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider;

    /** Per-call runtime context for userId extraction. */
    private volatile RuntimeContext currentCtx;

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    public SkillSaveTool(Path skillsDir, SkillIndexRepository indexRepository) {
        this(skillsDir, indexRepository, SkillEntry.SOURCE_AUTO_SYNTHESIZED, null);
    }

    public SkillSaveTool(Path skillsDir, SkillIndexRepository indexRepository, String source) {
        this(skillsDir, indexRepository, source, null);
    }

    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            String source,
            ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
        this.source = source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source;
        this.skillManageServiceProvider = skillManageServiceProvider;
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
            String userId = currentCtx != null ? currentCtx.getUserId() : null;
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            if (safeName.isBlank()) {
                return ToolResultBlock.error("skill_name 必须包含至少一个英文字母或数字");
            }
            // 检索名直接用 sanitized 名字,不再加 usr_<userId>_ 前缀;同名(任意来源/所有者)一律拒绝
            if (existsActiveInIndex(safeName)) {
                return ToolResultBlock.error("技能名 '" + safeName + "' 已存在，请改名后重试");
            }
            String desc = description == null ? "" : description.trim();
            String body = content == null ? "" : content.trim();
            while (body.startsWith("---")) {
                String stripped = stripFrontmatter(body);
                if (stripped.equals(body)) break;
                body = stripped;
            }

            int version = upsertVersion(safeName, desc, userId);
            String frontmatter = renderFrontmatter(safeName, desc, version);
            String full = frontmatter + body;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(safeName)
                            .description(desc)
                            .skillContent(full)
                            .source(source)
                            .build();

            boolean saved = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
            if (!saved) {
                return ToolResultBlock.error("技能保存失败，请重试");
            }

            // 同步写入 skill_manage 表,让管理页面可见
            syncToSkillManage(safeName, desc, body, userId);

            String msg = "技能保存成功 v" + version + " - " + skillsDir.resolve(safeName).resolve("SKILL.md");
            log.info("Skill saved: {} v{}", safeName, version);
            return ToolResultBlock.text(msg);
        } catch (Exception e) {
            log.error("Failed to save skill: {}", skillName, e);
            return ToolResultBlock.error("保存技能时出错: " + e.getMessage());
        }
    }

    private int upsertVersion(String name, String description, String ownerUserId) {
        if (indexRepository == null) {
            return 1;
        }
        int v = indexRepository.upsertOnSave(name, description, source, ownerUserId);
        return v > 0 ? v : 1;
    }

    /**
     * Cross-source name collision guard. Returns true when the name is free or already owned
     * by this tool's {@code source}; false when the name exists with a different source (in
     * which case the save must be rejected to keep {@code skill_index.source} immutable).
     */
    private boolean checkNameAvailable(String name) {
        if (indexRepository == null) return true;
        return indexRepository.checkNameAvailable(name, source);
    }

    /**
     * Variant of {@link #saveSkill} for programmatic callers (e.g. SkillSynthesisRunner) that
     * already have a complete skill body and want to stamp an extra {@code metric_tag} field
     * into the frontmatter. Skips the tool-call wiring and the stripFrontmatter step so any
     * metric_tag injected upstream by {@code withMetricTag} is preserved.
     *
     * @return true on success
     */
    public boolean saveSkillWithMetricTag(
            String skillName, String description, String body, String metricTag) {
        try {
            if (skillName == null || skillName.isBlank()) return false;
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            String desc = description == null ? "" : description.trim();
            String safeBody = body == null ? "" : body.trim();
            while (safeBody.startsWith("---")) {
                String stripped = stripFrontmatter(safeBody);
                if (stripped.equals(safeBody)) break;
                safeBody = stripped;
            }

            if (!checkNameAvailable(safeName)) {
                log.warn(
                        "Skill name '{}' already owned by another source; skipping save (metric_tag={})",
                        safeName,
                        metricTag);
                return false;
            }

            int version = upsertVersion(safeName, desc, null);
            String frontmatter = renderFrontmatter(safeName, desc, version, metricTag);
            String full = frontmatter + safeBody;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(safeName)
                            .description(desc)
                            .skillContent(full)
                            .source(source)
                            .build();
            boolean saved = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
            if (saved) {
                log.info("Skill saved (with metric_tag={}): {} v{}", metricTag, safeName, version);
            }
            return saved;
        } catch (Exception e) {
            log.error("Failed to save skill with metric_tag: {}", skillName, e);
            return false;
        }
    }

    /**
     * 检索名是否已被占用(仅 active 行算占用;blacklisted=已删除,名字可复用)。
     * 去掉 usr_<userId>_ 前缀后检索名全局唯一,故需在保存前显式判重。
     */
    private boolean existsActiveInIndex(String name) {
        if (indexRepository == null) return false;
        return indexRepository.findByName(name)
                .map(e -> SkillEntry.STATUS_ACTIVE.equals(e.status()))
                .orElse(false);
    }

    /**
     * PR5 - 同步写入 skill_manage 表,让 Agent 创建的 skill 在管理页面可见。
     * best-effort: 失败只 log warn,不影响 skill 保存结果。
     */
    private void syncToSkillManage(String retrievalName, String description, String content, String userId) {
        if (userId == null || userId.isBlank()) return;  // 匿名不写
        if (skillManageServiceProvider == null) return;
        com.agentscopea2a.v2.skillManager.service.SkillManageService svc = skillManageServiceProvider.getIfAvailable();
        if (svc == null) return;
        try {
            com.agentscopea2a.v2.skillManager.entity.Skill skill = new com.agentscopea2a.v2.skillManager.entity.Skill();
            // skill_manage.name 是显示名;Agent 创建的没有中文标题,用 description 兜底
            String displayName = (description != null && !description.isBlank()) ? description : retrievalName;
            skill.setName(displayName);
            skill.setDescription(description);
            skill.setContent(content);
            skill.setStatus("ACTIVE");
            skill.setCreatedAt(java.time.LocalDateTime.now());
            skill.setUpdatedAt(java.time.LocalDateTime.now());
            svc.createForAgent(skill, userId, retrievalName);
        } catch (Exception ex) {
            log.warn("syncToSkillManage failed for '{}': {}", retrievalName, ex.getMessage());
        }
    }

    /** Drop any YAML frontmatter the LLM may have prepended - we own this block. */
    public static String stripFrontmatter(String content) {
        if (content == null || content.isEmpty()) return "";
        Matcher m = FRONTMATTER.matcher(content);
        return m.find() && m.start() == 0 ? content.substring(m.end()) : content;
    }

    public static String renderFrontmatter(String name, String description, int version) {
        return renderFrontmatter(name, description, version, null);
    }

    public static String renderFrontmatter(String name, String description, int version, String metricTag) {
        // Escape only what YAML genuinely needs: double-quote the description and backslash
        // any literal " inside it. Names are already [a-z0-9_] from safeName().
        String safeDesc = description.replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: \"").append(safeDesc).append("\"\n")
                .append("version: ").append(version).append('\n')
                .append("last_evolved_at: ").append(LocalDate.now()).append('\n');
        if (metricTag != null && !metricTag.isBlank()) {
            sb.append("metric_tag: ").append(metricTag).append('\n');
        }
        sb.append("---\n\n");
        return sb.toString();
    }
}
