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

import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillTrialRun;
import com.agentscopea2a.v2.skillManager.mapper.SkillTrialRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 试运行记录服务。
 *
 * <p>问数本身由前端直调 {@code /ai/chat}(与 /chat 页共用链路,后端零改动),
 * 流结束后前端调本服务的记录接口落库;skillName 按 skillId 查 skill_manage 回填,
 * 且记录/列表均过可见性校验(私有未授权抛 SkillNotFound,不泄露存在性)。</p>
 *
 * <p>gauss 运行时 Flyway 关闭(flyway-core 9.22 对汇报为 PostgreSQL 9.2 的
 * openGauss 拒绝执行迁移),建表 SQL(V20261016.1__skill_trial_run.sql)需手工执行,
 * 这里按 {@code SkillRoutingMetadataRepository.ensureTable()} 的既有约定幂等兜底建表。</p>
 */
@Service
public class SkillTrialRunService {

    private static final Logger log = LoggerFactory.getLogger(SkillTrialRunService.class);

    /** question 超长截断,防止异常大文本落库 */
    private static final int QUESTION_MAX_LEN = 4000;
    /** skill_manage.name 列宽 128,回填时同步截断 */
    private static final int NAME_MAX_LEN = 128;
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;

    private final SkillTrialRunMapper trialRunMapper;
    private final SkillManageService skillManageService;
    private final DataSource dataSource;

    private volatile boolean tableEnsured;

    public SkillTrialRunService(SkillTrialRunMapper trialRunMapper,
                                SkillManageService skillManageService,
                                @Qualifier("gaussCustomerDataSource") DataSource dataSource) {
        this.trialRunMapper = trialRunMapper;
        this.skillManageService = skillManageService;
        this.dataSource = dataSource;
    }

    /**
     * 记录一次试运行。
     *
     * @return 落库后的记录(含 id/createdAt)
     */
    public SkillTrialRun record(Long skillId, String conversationId, String question, String userId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("ConversationIdRequired: 会话ID不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("QuestionRequired: 问数话术不能为空");
        }
        // 可见性校验 + skillName 回填(不信任前端传值);不可见/不存在抛 SkillNotFound
        Skill skill = skillManageService.getVisible(skillId, userId);
        SkillTrialRun record = SkillTrialRun.builder()
                .skillId(skillId)
                .skillName(truncate(skill.getName(), NAME_MAX_LEN))
                .conversationId(conversationId.trim())
                .question(truncate(question, QUESTION_MAX_LEN))
                .userId(userId)
                .build();
        ensureTable();
        trialRunMapper.insert(record);
        return record;
    }

    /**
     * 查询某 Skill 的历史试运行记录(倒序,限量)。
     *
     * @param onlyAfterSkillUpdate 为 true 时仅返回 skill 最后修改时间之后的记录
     *                             (试运行结果查看用);skill.updatedAt 缺失时退化为不过滤
     */
    public List<SkillTrialRun> list(Long skillId, String userId, int limit, boolean onlyAfterSkillUpdate) {
        Skill skill = skillManageService.getVisible(skillId, userId); // 不可见抛 SkillNotFound
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        LocalDateTime afterTime = onlyAfterSkillUpdate && skill.getUpdatedAt() != null
                ? skill.getUpdatedAt() : null;
        ensureTable();
        return trialRunMapper.selectBySkillId(skillId, safeLimit, afterTime);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 幂等建表兜底(见 V20261016.1__skill_trial_run.sql);失败仅告警不阻断,
     * 下次调用重试,建表前 insert 会抛 SQL 异常由全局异常处理兜住。
     */
    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
            String ddl = "CREATE TABLE IF NOT EXISTS skill_trial_run ("
                    + "id BIGSERIAL PRIMARY KEY, skill_id BIGINT NOT NULL,"
                    + "skill_name VARCHAR(128) NOT NULL, conversation_id VARCHAR(64) NOT NULL,"
                    + "question TEXT NOT NULL, user_id VARCHAR(128) NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";
            String ddlIndex = "CREATE INDEX IF NOT EXISTS idx_skill_trial_run_skill_id"
                    + " ON skill_trial_run (skill_id, id DESC)";
            try (Connection c = dataSource.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(ddlIndex)) {
                    ps.execute();
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_trial_run DDL failed (will retry): {}", e.getMessage());
            }
        }
    }
}
