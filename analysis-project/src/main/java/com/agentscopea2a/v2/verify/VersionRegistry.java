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
package com.agentscopea2a.v2.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Version Registry (V3.0 design §14, P1). Registers versioned snapshots of components
 * (Agent / Prompt / Skill / SemanticContract / RepairPolicy / Tool) so Golden Evaluation can pin a
 * version combination and Replay can reproduce. Pairs with {@link GoldenEvaluationRunner} and
 * {@link ReplayService}.
 */
@Component
public class VersionRegistry {

    private static final Logger log = LoggerFactory.getLogger(VersionRegistry.class);

    private final DataSource dataSource;

    public VersionRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Register a version snapshot; returns the generated versionId. Idempotent on (component, version). */
    public String register(String component, String componentRef, String version, String checksum, String releasedBy) {
        String versionId = "vreg-" + component.toLowerCase() + "-" + version + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String sql = "INSERT INTO agent_version_registry (version_id, component, component_ref, version, "
                + "checksum, released_by, status) VALUES (?,?,?,?,?,?,'candidate')";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            ps.setString(2, component);
            ps.setString(3, componentRef);
            ps.setString(4, version);
            ps.setString(5, checksum);
            ps.setString(6, releasedBy);
            ps.executeUpdate();
            log.info("VersionRegistry: registered {}:{}:{} -> {}", component, componentRef, version, versionId);
        } catch (Exception e) {
            log.warn("VersionRegistry: register failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return versionId;
    }

    public VersionRecord get(String versionId) {
        if (versionId == null) return null;
        String sql = "SELECT version_id, component, component_ref, version, checksum, released_by, "
                + "released_at, golden_eval_id, status FROM agent_version_registry WHERE version_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (Exception e) {
            log.warn("VersionRegistry: get failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return null;
    }

    /** Link a Golden evaluation id to a version (so the version's regression result is traceable). */
    public void linkGoldenEval(String versionId, String evalId) {
        String sql = "UPDATE agent_version_registry SET golden_eval_id=? WHERE version_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, evalId);
            ps.setString(2, versionId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("VersionRegistry: linkGoldenEval failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public void markStable(String versionId) {
        String sql = "UPDATE agent_version_registry SET status='stable' WHERE version_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("VersionRegistry: markStable failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    /** Latest registered version for a component (by released_at desc). */
    public VersionRecord latest(String component) {
        String sql = "SELECT version_id, component, component_ref, version, checksum, released_by, "
                + "released_at, golden_eval_id, status FROM agent_version_registry WHERE component=? "
                + "ORDER BY released_at DESC LIMIT 1";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, component);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (Exception e) {
            log.warn("VersionRegistry: latest failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return null;
    }

    public List<VersionRecord> listByComponent(String component, int limit) {
        List<VersionRecord> out = new ArrayList<>();
        String sql = "SELECT version_id, component, component_ref, version, checksum, released_by, "
                + "released_at, golden_eval_id, status FROM agent_version_registry WHERE component=? "
                + "ORDER BY released_at DESC LIMIT ?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, component);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (Exception e) {
            log.warn("VersionRegistry: listByComponent failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private static VersionRecord mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("released_at");
        return new VersionRecord(
                rs.getString("version_id"),
                rs.getString("component"),
                rs.getString("component_ref"),
                rs.getString("version"),
                rs.getString("checksum"),
                rs.getString("released_by"),
                ts == null ? null : ts.toInstant(),
                rs.getString("golden_eval_id"),
                rs.getString("status"));
    }
}
