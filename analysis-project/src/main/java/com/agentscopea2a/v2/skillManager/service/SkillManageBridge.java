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
     * <p>检索名直接使用去除首尾空格后的 skill 名字,支持中文并保留原始大小写。
     * 检索名全局唯一。本方法以 {@code skill.getRetrievalName()}
     * 作为"上次持久化的检索名"与按当前名字新算出的检索名对比:
     * <ul>
     *   <li>上次为空(首次创建):若新名未被占用则 upsert;已被其它 active skill 占用则抛
     *       {@link IllegalStateException}("SkillNameConflict"),不让添加(回滚外层 create 事务)。</li>
     *   <li>新名 == 上次名(未改名,或 sanitize 后相同):原地 upsert 更新 description/version。</li>
     *   <li>新名 != 上次名(改名):若新名未被占用,则 {@link SkillIndexRepository#renameRow}
     *       原地改主键,保留 version/usage/统计;否则抛 "SkillNameConflict"(回滚 update)。</li>
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
        String newRn = buildRetrievalName(skill.getName());
        String desc = skill.getDescription() == null ? "" : skill.getDescription();
        String previousRn = skill.getRetrievalName();

        try {
            // 1. 首次创建:previousRn 为空,直接 upsert;同名(任意来源)已存在则拒绝,不让添加
            if (previousRn == null || previousRn.isBlank()) {
                if (retrievalNameTaken(newRn)) {
                    throw new IllegalStateException("SkillNameConflict: " + skill.getName());
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

            // 3. 改名:新名已被其它 active skill 占用则拒绝(回滚整个 update),避免 PK 冲突 / 串号
            if (retrievalNameTaken(newRn)) {
                throw new IllegalStateException("SkillNameConflict: " + skill.getName());
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
        } catch (IllegalStateException ex) {
            // SkillNameConflict 等业务异常:向上传播,回滚外层 create/update 事务并提示用户
            throw ex;
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
     * @param retrievalName 检索名（与 Skill 展示名称一致），为 null 时跳过
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


    private String buildRetrievalName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("SkillNameMissing");
        }
        return skillName.trim();
    }

    /** 检索名是否已被其它 active skill 占用(blacklisted=已删除,名字可复用)。 */
    private boolean retrievalNameTaken(String name) {
        return indexRepo.findByName(name)
                .map(e -> SkillEntry.STATUS_ACTIVE.equals(e.status()))
                .orElse(false);
    }

}
