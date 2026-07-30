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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class SkillManageBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillManageBridge.class);

    private final SkillIndexRepository indexRepo;
    private final SkillVectorIndex vectorIndex;


    /** 单线程守护线程池，embedding 异步 upsert 不阻塞页面响应 */
    private static final ScheduledExecutorService EMBED_EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skill-page-embed");
                t.setDaemon(true);
                return t;
            });

    public SkillManageBridge(
            SkillIndexRepository indexRepo,
            SkillVectorIndex vectorIndex) {
        this.indexRepo = indexRepo;
        this.vectorIndex = vectorIndex;
    }

    /**
     * 同步页面 Skill 到检索索引（skill_index 表 + embedding）。
     *
     * @param skill 页面 Skill（必须已持久化，id 非空）
     * @return 映射后的检索名（优先 {@code usr_<userId>_<safeName>}，回退 {@code usr_<userId>_<id>}；userId 为空时为 {@code page_<id>}），未启用或失败时返回 null
     */
    public String syncToRetrievalIndex(Skill skill) {
        String retrievalName = buildRetrievalName(skill.getName(), skill.getId(), skill.getOwnerUserId());
        String desc = skill.getDescription() == null ? "" : skill.getDescription();

        try {
            // 1. 检查名称冲突（跨 source）
            if (!indexRepo.checkNameAvailable(retrievalName, SkillEntry.SOURCE_USER_GENERATED)) {
                log.warn("Skill retrieval name '{}' collision in skill_index, skip sync", retrievalName);
                return retrievalName;
            }

            // 2. 写 skill_index 表（版本自增）
            indexRepo.upsertOnSave(retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());


            log.info("Page skill synced to retrieval index: {} -> {}",
                    skill.getName(), retrievalName);
            return retrievalName;
        } catch (Exception ex) {
            log.warn("syncToRetrievalIndex failed for page skill '{}' (retrievalName={}): {}",
                    skill.getName(), retrievalName, ex.getMessage());
            return retrievalName;
        }
    }

    /**
     * 从检索索引移除页面 Skill（软删 skill_index 行）。
     *
     * @param retrievalName 检索名（{@code usr_<userId>_<safeName>} 或回退的 {@code usr_<userId>_<id>}），为 null 时跳过
     */
    public void removeFromRetrievalIndex(String retrievalName) {
        try {
            // 软删 skill_index 行（markBlacklist 保留历史计数，可恢复）
            indexRepo.markBlacklist(retrievalName);

            log.info("Page skill removed from retrieval index: {}", retrievalName);
        } catch (Exception ex) {
            log.warn("removeFromRetrievalIndex failed for '{}': {}", retrievalName, ex.getMessage());
        }
    }


    private String buildRetrievalName(String skillName, Long skillId, String userId) {
        if (userId == null || userId.isBlank()) return "page_" + skillId;
        String prefix = "usr_" + userId + "_";
        String safeName = sanitize(skillName);
        if (safeName == null) return prefix + skillId;
        return prefix + safeName;
    }


    private static String sanitize(String skillName) {
        if (skillName == null || skillName.isBlank()) return null;
        String safe = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        // 折叠连续 _ 并去首尾
        safe = safe.replaceAll("_+", "_").replaceAll("^_+|_$", "");
        return safe.isEmpty() ? null : safe;
    }
}
