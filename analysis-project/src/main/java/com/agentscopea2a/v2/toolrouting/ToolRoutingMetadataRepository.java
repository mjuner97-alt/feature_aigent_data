package com.agentscopea2a.v2.toolrouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** OpenGauss-backed routing metadata repository with a process-local TTL snapshot. */
public class ToolRoutingMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutingMetadataRepository.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ToolRoutingTagDictionary tagDictionary;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<ToolRoutingMetadata> cachedEnabled;
    private volatile long cachedAt;

    public ToolRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper,
                                         ToolRoutingTagDictionary tagDictionary, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tagDictionary = tagDictionary;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    @PostConstruct
    void initSchema() {
        ensureTable();
    }

    public List<ToolRoutingMetadata> findEnabled() {
        ensureTable();
        List<ToolRoutingMetadata> snapshot = cachedEnabled;
        if (snapshot != null && cacheTtlMillis > 0 && System.currentTimeMillis() - cachedAt < cacheTtlMillis) {
            return snapshot;
        }
        String sql = "SELECT tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, priority, enabled, updated_at "
                + "FROM tool_route_metadata WHERE enabled = TRUE ORDER BY priority DESC, tool_id";
        List<ToolRoutingMetadata> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.add(map(resultSet));
            }
            cachedEnabled = List.copyOf(result);
            cachedAt = System.currentTimeMillis();
        } catch (SQLException e) {
            log.warn("find enabled tool routing metadata failed: {}", e.getMessage());
        }
        return result;
    }

    public Optional<ToolRoutingMetadata> findEnabledByToolId(String toolId) {
        return findByToolId(toolId, true);
    }

    /** Management read path, including metadata intentionally disabled for staged rollout. */
    public Optional<ToolRoutingMetadata> findByToolId(String toolId) {
        return findByToolId(toolId, false);
    }

    public List<ToolRoutingMetadata> findAll() {
        ensureTable();
        String sql = "SELECT tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, priority, enabled, updated_at "
                + "FROM tool_route_metadata ORDER BY priority DESC, tool_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ToolRoutingMetadata> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(map(resultSet));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            log.warn("find all tool routing metadata failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<ToolRoutingMetadata> findByToolId(String toolId, boolean enabledOnly) {
        ensureTable();
        String sql = "SELECT tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, priority, enabled, updated_at "
                + "FROM tool_route_metadata WHERE tool_id = ?" + (enabledOnly ? " AND enabled = TRUE" : "");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("find tool routing metadata {} failed: {}", toolId, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean upsert(ToolRoutingMetadata metadata) {
        if (metadata.topicTags().isEmpty()) {
            throw new IllegalArgumentException("topicTags 至少需要一个标签");
        }
        if (metadata.metricTags().isEmpty()) {
            throw new IllegalArgumentException("metricTags 至少需要一个标签");
        }
        tagDictionary.validateEnabled(ToolRoutingTagType.TOPIC, metadata.topicTags());
        tagDictionary.validateEnabled(ToolRoutingTagType.METRIC, metadata.metricTags());
        tagDictionary.validateEnabled(ToolRoutingTagType.DIMENSION, metadata.dimensionTags());
        ensureTable();
        String sql = "INSERT INTO tool_route_metadata "
                + "(tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, priority, enabled, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now()) ON DUPLICATE KEY UPDATE "
                + "tool_type=VALUES(tool_type), description=VALUES(description), topic_tags=VALUES(topic_tags), metric_tags=VALUES(metric_tags), "
                + "dimension_tags=VALUES(dimension_tags), priority=VALUES(priority), enabled=VALUES(enabled), updated_at=now()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.toolId());
            statement.setString(2, metadata.toolType().name());
            statement.setString(3, metadata.description());
            statement.setString(4, json(metadata.topicTags()));
            statement.setString(5, json(metadata.metricTags()));
            statement.setString(6, json(metadata.dimensionTags()));
            statement.setInt(7, metadata.priority());
            statement.setBoolean(8, metadata.enabled());
            boolean updated = statement.executeUpdate() > 0;
            if (updated) {
                invalidateCache();
            }
            return updated;
        } catch (SQLException e) {
            log.warn("upsert tool routing metadata {} failed: {}", metadata.toolId(), e.getMessage());
            return false;
        }
    }

    public void invalidateCache() {
        cachedEnabled = null;
    }

    private ToolRoutingMetadata map(ResultSet resultSet) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new ToolRoutingMetadata(resultSet.getString("tool_id"),
                ToolRoutingToolType.valueOf(resultSet.getString("tool_type")), resultSet.getString("description"),
                parse(resultSet.getString("topic_tags")), parse(resultSet.getString("metric_tags")),
                parse(resultSet.getString("dimension_tags")),
                resultSet.getInt("priority"), resultSet.getBoolean("enabled"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private List<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception e) {
            log.warn("invalid tool routing metadata JSON array: {}", e.getMessage());
            return List.of();
        }
    }

    private String json(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize tool routing metadata", e);
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
            String ddl = "CREATE TABLE IF NOT EXISTS tool_route_metadata ("
                    + "tool_id VARCHAR(128) PRIMARY KEY, tool_type VARCHAR(16) NOT NULL,"
                    + "description VARCHAR(3000) NOT NULL DEFAULT '', topic_tags TEXT NOT NULL DEFAULT '[]', metric_tags TEXT NOT NULL DEFAULT '[]',"
                    + "dimension_tags TEXT NOT NULL DEFAULT '[]', priority INT NOT NULL DEFAULT 0,"
                    + "enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(ddl)) {
                statement.execute();
                try (PreparedStatement addColumn = connection.prepareStatement(
                        "ALTER TABLE tool_route_metadata ADD COLUMN topic_tags TEXT NOT NULL DEFAULT '[]'")) {
                    try {
                        addColumn.execute();
                    } catch (SQLException alreadyExists) {
                        // Existing databases created by V20260903.1 already contain the column
                        // after V20260903.2; openGauss has no ADD COLUMN IF NOT EXISTS syntax.
                        if (isDuplicateColumn(alreadyExists)) {
                            log.debug("tool_route_metadata.topic_tags already exists: {}", alreadyExists.getMessage());
                        } else {
                            throw alreadyExists;
                        }
                    }
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("tool_route_metadata DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    private static boolean isDuplicateColumn(SQLException error) {
        return "42701".equals(error.getSQLState())
                || (error.getMessage() != null && error.getMessage().toLowerCase().contains("already exists"));
    }
}
