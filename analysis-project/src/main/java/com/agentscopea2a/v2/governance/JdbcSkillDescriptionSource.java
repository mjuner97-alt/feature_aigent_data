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
package com.agentscopea2a.v2.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * skill_manage 的 GaussDB JDBC 只读访问, 与 {@code SkillRoutingMetadataRepository}
 * 同一数据源 (gaussCustomerDataSource)。查询失败时返回空列表并告警, 不阻塞治理检测
 * (上游按 degraded 处理)。
 */
public class JdbcSkillDescriptionSource implements SkillDescriptionSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcSkillDescriptionSource.class);

    private final DataSource dataSource;
    private volatile boolean warned;

    public JdbcSkillDescriptionSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<SkillDescriptionRow> allActiveSkills() {
        String sql = "SELECT id, name, description, owner_user_id, retrieval_name, visibility "
                + "FROM skill_manage WHERE status = 'ACTIVE' AND deleted_at IS NULL "
                + "AND retrieval_name IS NOT NULL";
        List<SkillDescriptionRow> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new SkillDescriptionRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("owner_user_id"),
                        rs.getString("retrieval_name"),
                        rs.getString("visibility")));
            }
            warned = false;
        } catch (SQLException e) {
            // 只在状态翻转时打一条 WARN, 避免治理轮询把日志刷爆
            if (!warned) {
                log.warn("allActiveSkills query failed: {}", e.getMessage());
                warned = true;
            }
        }
        return result;
    }
}
