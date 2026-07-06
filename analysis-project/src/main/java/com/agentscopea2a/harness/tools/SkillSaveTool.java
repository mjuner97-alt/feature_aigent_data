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

import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.SkillIndexRepository;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;

    /** Single-thread daemon so embedding upserts never delay the save_skill tool reply. */
    private static final ScheduledExecutorService EMBED_EXEC =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "skill-embed");
                        t.setDaemon(true);
                        return t;
                    });

    /** Repository may be {@code null} when wired in legacy single-arg paths (tests, etc.). */
    public SkillSaveTool(Path skillsDir, SkillIndexRepository indexRepository) {
        this(skillsDir, indexRepository, null, null);
    }

    /**
     * PR3 constructor — adds the optional {@link SkillVectorIndex} + {@link EmbeddingClient}
     * pair. When both are non-null, every successful save kicks off an async embedding upsert
     * so the new skill becomes retrievable on the next request. Either being null silently
     * skips that step (PR3 retrieval is opt-in).
     */
    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
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
                maybeEmbedAsync(safeName, desc);
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
            String safeBody = stripFrontmatter(body == null ? "" : body.trim());

            int version = upsertVersion(safeName, desc);
            String frontmatter = renderFrontmatter(safeName, desc, version, metricTag);
            String full = frontmatter + safeBody;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(safeName)
                            .description(desc)
                            .skillContent(full)
                            .source("auto_synthesized")
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
     * Fire-and-forget embedding upsert (PR3). Skipped when either dependency is unwired —
     * preserves the manual save_skill path's behaviour when retrieval is disabled. We embed
     * "{name} {description}" rather than the full SKILL.md body because (a) description is
     * what discriminates skills semantically, and (b) full-body embeddings would change every
     * version bump even when the skill's intent didn't.
     */
    private void maybeEmbedAsync(String name, String description) {
        if (vectorIndex == null || embeddingClient == null) return;
        final String text = (name + " " + description).trim();
        if (text.isEmpty()) return;
        EMBED_EXEC.submit(
                () -> {
                    try {
                        float[] vec = embeddingClient.embed(text);
                        if (vec == null) {
                            log.debug("Embedding null for skill {}, vector upsert skipped", name);
                            return;
                        }
                        // fingerprint is owned by PR2's synthesis path; manual saves leave it
                        // null and rely on L2 for retrieval.
                        vectorIndex.upsertVector(name, null, vec);
                    } catch (Exception ex) {
                        log.warn("Async embedding upsert for {} failed: {}", name, ex.getMessage());
                    }
                });
    }

    /** Drop any YAML frontmatter the LLM may have prepended — we own this block. */
    static String stripFrontmatter(String content) {
        if (content == null || content.isEmpty()) return "";
        Matcher m = FRONTMATTER.matcher(content);
        return m.find() && m.start() == 0 ? content.substring(m.end()) : content;
    }

    static String renderFrontmatter(String name, String description, int version) {
        return renderFrontmatter(name, description, version, null);
    }

    static String renderFrontmatter(String name, String description, int version, String metricTag) {
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
