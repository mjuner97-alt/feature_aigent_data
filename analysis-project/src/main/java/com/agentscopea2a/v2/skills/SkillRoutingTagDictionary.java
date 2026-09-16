package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.toolrouting.ToolRoutingTag;
import com.agentscopea2a.v2.toolrouting.ToolRoutingTagType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skill 侧独立的路由标签词典。Skill 路由保留领域 (DOMAIN) 与业务主题 (TOPIC) 两类标签,
 * 与工具路由的 {@code tool_route_tag_dictionary} 完全分离; 建表时把工具词典中
 * 已有的 DOMAIN/TOPIC 标签拷贝一份作种子, 保证存量 skill 元数据的标签校验不断。
 */
public class SkillRoutingTagDictionary {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutingTagDictionary.class);

    private final DataSource dataSource;
    private volatile boolean tableEnsured;

    public SkillRoutingTagDictionary(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void initSchema() {
        ensureTable();
    }

    public List<ToolRoutingTag> findEnabled(ToolRoutingTagType tagType) {
        ensureTable();
        requireSkillTagType(tagType);
        String sql = "SELECT tag_name, description, enabled FROM skill_route_tag_dictionary "
                + "WHERE tag_type = ? AND enabled = TRUE ORDER BY tag_name";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tagType.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<ToolRoutingTag> tags = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    tags.add(new ToolRoutingTag(tagType, resultSet.getString("tag_name"),
                            resultSet.getString("description"), resultSet.getBoolean("enabled")));
                }
                return List.copyOf(tags);
            }
        } catch (SQLException e) {
            log.warn("find enabled skill routing {} tags failed: {}", tagType, e.getMessage());
            return List.of();
        }
    }

    /** Rejects skill metadata that refers to absent or disabled canonical tags. */
    public void validateEnabled(ToolRoutingTagType tagType, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        Map<String, String> enabled = new LinkedHashMap<>();
        for (ToolRoutingTag tag : findEnabled(tagType)) {
            enabled.put(tag.tagName().toLowerCase(Locale.ROOT), tag.tagName());
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank() || !enabled.containsKey(tag.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("未登记或未启用的" + skillTagTypeLabel(tagType) + "标签: " + tag);
            }
        }
    }

    public boolean upsert(ToolRoutingTag tag) {
        ensureTable();
        requireSkillTagType(tag.tagType());
        String sql = "INSERT INTO skill_route_tag_dictionary (tag_type, tag_name, description, enabled, updated_at) "
                + "VALUES (?, ?, COALESCE(?, ' '), ?, now()) ON DUPLICATE KEY UPDATE "
                + "description=VALUES(description), enabled=VALUES(enabled), updated_at=now()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tag.tagType().name());
            statement.setString(2, tag.tagName());
            statement.setString(3, tag.description() == null ? "" : tag.description());
            statement.setBoolean(4, tag.enabled());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("upsert skill routing tag {} failed", tag.tagName(), e);
            return false;
        }
    }

    private static void requireSkillTagType(ToolRoutingTagType tagType) {
        if (tagType != ToolRoutingTagType.DOMAIN && tagType != ToolRoutingTagType.TOPIC) {
            throw new IllegalArgumentException("SkillTagTypeNotAllowed: Skill 词典仅支持 DOMAIN/TOPIC, got " + tagType);
        }
    }

    private static String skillTagTypeLabel(ToolRoutingTagType tagType) {
        return tagType == ToolRoutingTagType.DOMAIN ? "领域" : "业务主题";
    }

    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
            String ddl = "CREATE TABLE IF NOT EXISTS skill_route_tag_dictionary ("
                    + "tag_type VARCHAR(16) NOT NULL, tag_name VARCHAR(64) NOT NULL,"
                    + "description VARCHAR(500) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "PRIMARY KEY (tag_type, tag_name))";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(ddl)) {
                statement.execute();
                repairTagTypeConstraint(connection);
                seedFromToolDictionary(connection);
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_route_tag_dictionary DDL failed (will retry)", e);
            }
        }
    }

    private void repairTagTypeConstraint(Connection connection) throws SQLException {
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE skill_route_tag_dictionary DROP CONSTRAINT IF EXISTS ck_skill_route_tag_dictionary_type")) {
            drop.execute();
        }
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE skill_route_tag_dictionary ADD CONSTRAINT ck_skill_route_tag_dictionary_type "
                        + "CHECK (tag_type IN ('DOMAIN', 'TOPIC'))")) {
            add.execute();
        }
    }

    /** 首次建表 (表为空) 时, 把工具词典的 DOMAIN/TOPIC 标签拷贝一份作为种子。 */
    private void seedFromToolDictionary(Connection connection) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM skill_route_tag_dictionary");
             ResultSet resultSet = count.executeQuery()) {
            if (resultSet.next() && resultSet.getInt(1) > 0) {
                return;
            }
        }
        try (PreparedStatement seed = connection.prepareStatement(
                "INSERT INTO skill_route_tag_dictionary (tag_type, tag_name, description, enabled, created_at, updated_at) "
                        + "SELECT tag_type, tag_name, description, enabled, now(), now() "
                        + "FROM tool_route_tag_dictionary WHERE tag_type IN ('DOMAIN', 'TOPIC') AND enabled = TRUE")) {
            int copied = seed.executeUpdate();
            if (copied > 0) {
                log.info("SkillRoutingTagDictionary: seeded {} DOMAIN/TOPIC tags from tool_route_tag_dictionary", copied);
            }
        } catch (SQLException e) {
            // 工具词典表尚未建好时种子拷贝失败不阻塞, 管理员可稍后手动新增
            log.warn("seed skill tag dictionary from tool dictionary failed: {}", e.getMessage());
        }
    }
}
