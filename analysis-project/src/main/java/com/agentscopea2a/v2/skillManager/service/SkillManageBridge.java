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

import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkillManageBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillManageBridge.class);

    private final SkillIndexRepository indexRepo;

    public SkillManageBridge(SkillIndexRepository indexRepo) {
        this.indexRepo = indexRepo;
    }

    /**
     * 同步页面 Skill 到检索索引（skill_index 表）。
     *
     * <p>检索名由 skill 名字派生({@code usr_<userId>_<safeName>} / 回退 {@code usr_<userId>_<id>} /
     * userId 为空时 {@code page_<id>})。本方法以 {@code skill.getRetrievalName()} 作为"上次持久化
     * 的检索名"与按当前名字新算出的检索名对比:
     * <ul>
     *   <li>上次为空(首次创建):直接 upsert 新检索名。</li>
     *   <li>新名 == 上次名(未改名,或 sanitize 后相同):原地 upsert 更新 description/version。</li>
     *   <li>新名 != 上次名(改名):若新名未被其它 skill 占用,则 {@link SkillIndexRepository#renameRow}
     *       原地改主键,保留 version/usage/统计;否则放弃改名、保留旧检索名(避免 PK 冲突串号)。</li>
     * </ul>
     *
     * <p><b>调用方须将返回值回写 {@code skill_manage.retrieval_name}</b>(见 SkillManageService
     * 的 update / approveDraft),否则两表会指向不同行——这正是"改名后检索名看起来没变、
     * 以为没改成功"的根因。
     *
     * @param skill 页面 Skill（必须已持久化，id 非空；update/approveDraft 路径下 retrievalName 为上次值）
     * @return 当前应使用的检索名（调用方据此回写 skill_manage.retrieval_name）
     */
    public String syncToRetrievalIndex(Skill skill) {
        String newRn = buildRetrievalName(skill.getName(), skill.getId(), skill.getOwnerUserId());
        String desc = skill.getDescription() == null ? "" : skill.getDescription();
        String previousRn = skill.getRetrievalName();

        try {
            // 1. 首次创建:previousRn 为空,直接 upsert
            if (previousRn == null || previousRn.isBlank()) {
                if (!indexRepo.checkNameAvailable(newRn, SkillEntry.SOURCE_USER_GENERATED)) {
                    log.warn("Skill retrieval name '{}' collision in skill_index, skip sync", newRn);
                    return newRn;
                }
                indexRepo.upsertOnSave(newRn, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
                log.info("Page skill synced to retrieval index: {} -> {}", skill.getName(), newRn);
                return newRn;
            }

            // 2. 未改名(sanitize 后相同):原地 upsert 更新 description/version
            if (newRn.equals(previousRn)) {
                indexRepo.upsertOnSave(previousRn, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
                log.info("Page skill synced to retrieval index (in-place): {} -> {}", skill.getName(), previousRn);
                return previousRn;
            }

            // 3. 改名:新名已被其它 skill 占用则保留旧检索名,避免 PK 冲突 / 串号
            if (indexRepo.findByName(newRn).isPresent()) {
                log.warn("Rename target '{}' already exists in skill_index, keep old '{}'", newRn, previousRn);
                indexRepo.upsertOnSave(previousRn, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
                return previousRn;
            }

            // 4. 改名:原地重命名 skill_index 行主键,保留 version/usage/统计
            int renamed = indexRepo.renameRow(previousRn, newRn, desc);
            if (renamed > 0) {
                log.info("Page skill renamed in retrieval index: {} -> {}", previousRn, newRn);
                return newRn;
            }
            // 旧行不存在(异常情形):回退为新建新名
            log.warn("renameRow({} -> {}) updated 0 rows, fallback to upsert new", previousRn, newRn);
            indexRepo.upsertOnSave(newRn, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
            return newRn;
        } catch (Exception ex) {
            log.warn("syncToRetrievalIndex failed for page skill '{}' (previousRn={}, newRn={}): {}",
                    skill.getName(), previousRn, newRn, ex.getMessage());
            // 失败时优先保留旧检索名,与 skill_index 当前实际状态保持一致;首次创建则回退新名
            return (previousRn != null && !previousRn.isBlank()) ? previousRn : newRn;
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
