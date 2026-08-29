package com.agentscopea2a.v2.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** OpenGauss-backed repository for coarse capability routing metadata. */
public class CapabilityRepository {
    private static final Logger log = LoggerFactory.getLogger(CapabilityRepository.class);
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private volatile boolean ensured;

    public CapabilityRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @PostConstruct
    void initSchema() { ensureSchema(); }

    public List<CapabilityMetadata> findActive() {
        ensureSchema();
        List<CapabilityMetadata> result = new ArrayList<>();
        String sql = "SELECT capability_name, short_summary, aliases, keywords, domain_tags, priority, active "
                + "FROM capability_registry WHERE active = TRUE ORDER BY priority DESC, capability_name";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(new CapabilityMetadata(rs.getString(1), rs.getString(2),
                    parse(rs.getString(3)), parse(rs.getString(4)), parse(rs.getString(5)), rs.getInt(6), rs.getBoolean(7)));
        } catch (SQLException e) { log.warn("findActive capabilities failed: {}", e.getMessage()); }
        return result;
    }

    public Map<String, List<String>> findActiveSkillBindings() {
        ensureSchema();
        Map<String, List<String>> result = new HashMap<>();
        String sql = "SELECT capability_name, skill_name FROM skill_capability_binding WHERE active = TRUE";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        } catch (SQLException e) { log.warn("findActive capability bindings failed: {}", e.getMessage()); }
        return result;
    }

    /** Inserts or refreshes a capability record discovered from explicit Skill frontmatter. */
    public boolean upsert(CapabilityMetadata metadata) {
        ensureSchema();
        String sql = "INSERT INTO capability_registry (capability_name, short_summary, aliases, keywords, domain_tags, priority, active, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON DUPLICATE KEY UPDATE updated_at = now()";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metadata.name()); ps.setString(2, metadata.shortSummary());
            ps.setString(3, json(metadata.aliases())); ps.setString(4, json(metadata.keywords()));
            ps.setString(5, json(metadata.domainTags())); ps.setInt(6, metadata.priority()); ps.setBoolean(7, metadata.active());
            ps.executeUpdate(); return true;
        } catch (SQLException e) { log.warn("upsert capability {} failed: {}", metadata.name(), e.getMessage()); return false; }
    }

    /** Adds a missing Skill-capability binding without altering an existing administrator record. */
    public boolean bindIfAbsent(String skillName, String capabilityName) {
        ensureSchema();
        String sql = "INSERT INTO skill_capability_binding (skill_name, capability_name, active, updated_at) "
                + "VALUES (?, ?, TRUE, now()) ON DUPLICATE KEY UPDATE updated_at = updated_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName); ps.setString(2, capabilityName); ps.executeUpdate(); return true;
        } catch (SQLException e) { log.warn("bind {} -> {} failed: {}", skillName, capabilityName, e.getMessage()); return false; }
    }

    private void ensureSchema() {
        if (ensured) return;
        synchronized (this) {
            if (ensured) return;
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS capability_registry (capability_name VARCHAR(128) PRIMARY KEY, short_summary VARCHAR(500) NOT NULL DEFAULT '', aliases TEXT NOT NULL DEFAULT '[]', keywords TEXT NOT NULL DEFAULT '[]', domain_tags TEXT NOT NULL DEFAULT '[]', priority INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMP NOT NULL DEFAULT now())");
                s.execute("CREATE TABLE IF NOT EXISTS skill_capability_binding (skill_name VARCHAR(128) NOT NULL, capability_name VARCHAR(128) NOT NULL, priority INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMP NOT NULL DEFAULT now(), PRIMARY KEY(skill_name, capability_name))");
                ensured = true;
            } catch (SQLException e) { log.warn("capability routing DDL failed (will retry): {}", e.getMessage()); }
        }
    }

    private List<String> parse(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return mapper.readValue(value, LIST); } catch (Exception e) { return List.of(); }
    }

    private String json(List<String> value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot serialize capability metadata", e); }
    }
}
