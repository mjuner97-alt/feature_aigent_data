package com.agentscopea2a.v2.toolrouting;

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

/** OpenGauss-backed source of canonical Skill and Tool routing tags. */
public class ToolRoutingTagDictionary {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutingTagDictionary.class);

    private final DataSource dataSource;
    private volatile boolean tableEnsured;

    public ToolRoutingTagDictionary(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void initSchema() {
        ensureTable();
    }

    public List<ToolRoutingTag> findEnabled(ToolRoutingTagType tagType) {
        ensureTable();
        String sql = "SELECT tag_type, tag_name, description, enabled FROM tool_route_tag_dictionary "
                + "WHERE tag_type = ? AND enabled = TRUE ORDER BY tag_name";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tagType.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<ToolRoutingTag> tags = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    tags.add(new ToolRoutingTag(ToolRoutingTagType.valueOf(resultSet.getString("tag_type")),
                            resultSet.getString("tag_name"), resultSet.getString("description"),
                            resultSet.getBoolean("enabled")));
                }
                return List.copyOf(tags);
            }
        } catch (SQLException e) {
            log.warn("find enabled {} routing tags failed: {}", tagType, e.getMessage());
            return List.of();
        }
    }

    /** Rejects metadata that refers to absent or disabled canonical tags. */
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
                throw new IllegalArgumentException("未登记或未启用的" + tagType + "标签: " + tag);
            }
        }
    }

    public boolean upsert(ToolRoutingTag tag) {
        ensureTable();
        String sql = "INSERT INTO tool_route_tag_dictionary (tag_type, tag_name, description, enabled, updated_at) "
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
            log.warn("upsert routing tag {}:{} failed", tag.tagType(), tag.tagName(), e);
            return false;
        }
    }

    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
            String ddl = "CREATE TABLE IF NOT EXISTS tool_route_tag_dictionary ("
                    + "tag_type VARCHAR(16) NOT NULL, tag_name VARCHAR(64) NOT NULL,"
                    + "description VARCHAR(500) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "PRIMARY KEY (tag_type, tag_name))";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(ddl)) {
                statement.execute();
                repairTagTypeConstraint(connection);
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("tool_route_tag_dictionary DDL failed (will retry)", e);
            }
        }
    }

    private void repairTagTypeConstraint(Connection connection) throws SQLException {
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE tool_route_tag_dictionary DROP CONSTRAINT IF EXISTS ck_tool_route_tag_dictionary_type")) {
            drop.execute();
        }
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE tool_route_tag_dictionary ADD CONSTRAINT ck_tool_route_tag_dictionary_type "
                        + "CHECK (tag_type IN ('DOMAIN', 'TOPIC', 'METRIC', 'DIMENSION'))")) {
            add.execute();
        }
    }
}
