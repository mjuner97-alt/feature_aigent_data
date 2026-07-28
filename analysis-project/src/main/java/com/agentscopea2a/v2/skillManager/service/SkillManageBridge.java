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
package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 桥接组件：把页面创建/更新的 Skill 同步到检索链路能看到的存储。
 *
 * <p>页面 Skill 只写了 {@code skill_manage} 表，而对话加载链路（{@code SkillRetrievalHook}）
 * 只从 {@code skill_index} 表 + 文件系统 {@code SKILL.md} 读取。本桥接在页面 Skill 写操作后，
 * 同步写入：
 * <ol>
 *   <li>{@code skill_index} 表（版本自增 + source=user_generated）</li>
 *   <li>{@code {workspace}/skills-user/{retrievalName}/SKILL.md} 文件</li>
 *   <li>异步计算 embedding 并写入 {@code skill_index.embedding}</li>
 * </ol>
 *
 * <p>名称映射：页面 Skill 的 name 是中文/任意字符串，而 {@code skill_index.name} 是 PRIMARY KEY
 * 要求 {@code [a-z0-9_]}，因此用 {@code page_<skillId>} 格式作为检索名，存入
 * {@code skill_manage.retrieval_name} 列。
 *
 * <p>所有写操作均为 best-effort：失败只 log warn，不阻断主流程。
 */
public class SkillManageBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillManageBridge.class);

    private final SkillIndexRepository indexRepo;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final Path skillsUserDir;
    private final boolean enabled;

    /** 单线程守护线程池，embedding 异步 upsert 不阻塞页面响应 */
    private static final ScheduledExecutorService EMBED_EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skill-page-embed");
                t.setDaemon(true);
                return t;
            });

    public SkillManageBridge(
            SkillIndexRepository indexRepo,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            Path skillsUserDir,
            boolean enabled) {
        this.indexRepo = indexRepo;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.skillsUserDir = skillsUserDir;
        this.enabled = enabled;
    }

    /**
     * 同步页面 Skill 到检索索引（skill_index 表 + SKILL.md + embedding）。
     *
     * @param skill 页面 Skill（必须已持久化，id 非空）
     * @return 映射后的检索名（{@code page_<id>}），未启用或失败时返回 null
     */
    public String syncToRetrievalIndex(Skill skill) {
        if (!enabled || skill == null || skill.getId() == null) return null;

        String retrievalName = buildRetrievalName(skill.getId());
        String desc = skill.getDescription() == null ? "" : skill.getDescription();
        String body = skill.getContent() == null ? "" : skill.getContent();

        try {
            // 1. 检查名称冲突（跨 source）
            if (!indexRepo.checkNameAvailable(retrievalName, SkillEntry.SOURCE_USER_GENERATED)) {
                log.warn("Skill retrieval name '{}' collision in skill_index, skip sync", retrievalName);
                return retrievalName;
            }

            // 2. 写 skill_index 表（版本自增）
            int version = indexRepo.upsertOnSave(retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());

            // 3. 写 SKILL.md 文件（复用 SkillSaveTool 的 frontmatter 渲染逻辑）
            String frontmatter = SkillSaveTool.renderFrontmatter(retrievalName, desc, version);
            String full = frontmatter + body;
            AgentSkill agentSkill = AgentSkill.builder()
                    .name(retrievalName)
                    .description(desc)
                    .skillContent(full)
                    .source(SkillEntry.SOURCE_USER_GENERATED)
                    .build();
            boolean saved = SkillFileSystemHelper.saveSkills(skillsUserDir, List.of(agentSkill), true);
            if (!saved) {
                log.warn("Failed to write SKILL.md for page skill '{}'", retrievalName);
                return retrievalName;
            }

            // 4. 异步计算 embedding
            maybeEmbedAsync(retrievalName, desc);

            log.info("Page skill synced to retrieval index: {} -> {} v{}",
                    skill.getName(), retrievalName, version);
            return retrievalName;
        } catch (Exception ex) {
            log.warn("syncToRetrievalIndex failed for page skill '{}' (retrievalName={}): {}",
                    skill.getName(), retrievalName, ex.getMessage());
            return retrievalName;
        }
    }

    /**
     * 从检索索引移除页面 Skill（软删 skill_index 行 + 删 SKILL.md 目录）。
     *
     * @param retrievalName 检索名（{@code page_<id>}），为 null 时跳过
     */
    public void removeFromRetrievalIndex(String retrievalName) {
        if (!enabled || retrievalName == null || retrievalName.isBlank()) return;

        try {
            // 1. 软删 skill_index 行（markBlacklist 保留历史计数，可恢复）
            indexRepo.markBlacklist(retrievalName);

            // 2. 删 SKILL.md 文件目录
            Path skillDir = skillsUserDir.resolve(retrievalName);
            deleteRecursively(skillDir);

            log.info("Page skill removed from retrieval index: {}", retrievalName);
        } catch (Exception ex) {
            log.warn("removeFromRetrievalIndex failed for '{}': {}", retrievalName, ex.getMessage());
        }
    }

    /**
     * PR5 - 把一个已有 Skill 复制到目标用户的检索空间(引用场景)。
     * 写 skill_index + 写 SKILL.md + 异步 embedding,三步 best-effort。
     *
     * @param source           源 Skill(从 skill_manage 表读出)
     * @param refRetrievalName 引用副本的检索名(如 ref_page_42__u_user_001)
     * @param userId           引用者 userId
     */
    public void forkToUserSpace(Skill source, String refRetrievalName, String userId) {
        if (!enabled || source == null || refRetrievalName == null || userId == null) return;

        String desc = source.getDescription() == null ? "" : source.getDescription();
        String body = source.getContent() == null ? "" : source.getContent();

        try {
            // 1. 写 skill_index 表(owner_user_id = 引用者)
            int version = indexRepo.upsertOnSave(refRetrievalName, desc,
                SkillEntry.SOURCE_USER_GENERATED, userId);

            // 2. 写 SKILL.md 文件(直接用源 content 生成,不依赖原文件存在)
            String frontmatter = SkillSaveTool.renderFrontmatter(refRetrievalName, desc, version);
            String full = frontmatter + body;
            AgentSkill agentSkill = AgentSkill.builder()
                    .name(refRetrievalName)
                    .description(desc)
                    .skillContent(full)
                    .source(SkillEntry.SOURCE_USER_GENERATED)
                    .build();
            boolean saved = SkillFileSystemHelper.saveSkills(skillsUserDir, List.of(agentSkill), true);
            if (!saved) {
                log.warn("Failed to write SKILL.md for forked skill '{}'", refRetrievalName);
                return;
            }

            // 3. 异步计算 embedding
            maybeEmbedAsync(refRetrievalName, desc);

            log.info("Forked skill to user space: {} -> {} (userId={})",
                    source.getName(), refRetrievalName, userId);
        } catch (Exception ex) {
            log.warn("forkToUserSpace failed for '{}' (refRetrievalName={}, userId={}): {}",
                    source.getName(), refRetrievalName, userId, ex.getMessage());
        }
    }

    /** 构建检索名：{@code page_<skillId>} */
    private String buildRetrievalName(Long skillId) {
        return "page_" + skillId;
    }

    /**
     * 异步计算 embedding 并写入 skill_index.embedding。
     * 复用 SkillSaveTool 的逻辑：embed "{name} {description}" 而非全文。
     */
    private void maybeEmbedAsync(String name, String description) {
        if (vectorIndex == null || embeddingClient == null) return;
        final String text = (name + " " + description).trim();
        if (text.isEmpty()) return;
        EMBED_EXEC.submit(() -> {
            try {
                float[] vec = embeddingClient.embed(text);
                if (vec == null) {
                    log.debug("Embedding null for page skill {}, vector upsert skipped", name);
                    return;
                }
                vectorIndex.upsertVector(name, null, vec);
            } catch (Exception ex) {
                log.warn("Async embedding upsert for page skill {} failed: {}", name, ex.getMessage());
            }
        });
    }

    /** 递归删除目录及其内容 */
    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))  // 先删文件再删目录
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ex) {
                            log.debug("Failed to delete {}: {}", p, ex.getMessage());
                        }
                    });
        } catch (Exception ex) {
            log.debug("deleteRecursively failed for {}: {}", dir, ex.getMessage());
        }
    }
}
