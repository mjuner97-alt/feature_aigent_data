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
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
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
    /**
     * {@code user_generated} when wired for the {@code skill_save} tool (writes to
     * {@code skills-user/}); {@code auto_synthesized} when wired for any of the auto paths
     * (W2/W3/W4/W5, writes to {@code skills-auto/}). Persisted into {@code skill_index.source}
     * on insert and never overwritten on update.
     */
    private final String source;

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
        this(skillsDir, indexRepository, null, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
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
        this(skillsDir, indexRepository, vectorIndex, embeddingClient, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
    }

    /**
     * Full constructor - lets the caller pin the {@code source} so the same tool class backs
     * both the user-facing {@code skill_save} tool (writes to {@code skills-user/} with
     * {@code source=user_generated}) and the auto-distill/evolve paths (write to
     * {@code skills-auto/} with {@code source=auto_synthesized}).
     */
    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            String source) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.source = source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source;
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
            String body = content == null ? "" : content.trim();
            // Loop-strip all frontmatter blocks — LLM may generate multiple YAML blocks
            while (body.startsWith("---")) {
                String stripped = stripFrontmatter(body);
                if (stripped.equals(body)) break;
                body = stripped;
            }

            if (!checkNameAvailable(safeName)) {
                return ToolResultBlock.error(
                        "技能名 '" + safeName + "' 已被另一来源占用，请改名后重试");
            }

            int version = upsertVersion(safeName, desc);
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
        int v = indexRepository.upsertOnSave(name, description, source);
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
            // Loop-strip all frontmatter blocks — LLM may generate multiple YAML blocks
            // despite instructions saying "no frontmatter in content". A single stripFrontmatter
            // only removes the first block, leaving subsequent ones to appear as duplicate
            // frontmatter in the final file.
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

            int version = upsertVersion(safeName, desc);
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
    public static String stripFrontmatter(String content) {
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
