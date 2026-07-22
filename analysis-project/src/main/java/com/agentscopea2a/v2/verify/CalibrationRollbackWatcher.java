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

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-rollback watcher (V4.0 §31.2 完整自动优化闭环 - 自动上线 + 回滚). After
 * {@link QualityOptimizationLoop#applyThresholdTweaks} applies a threshold tweak and kicks off a
 * Golden evaluation, it registers the eval here. This watcher polls on a fixed delay:
 *
 * <ul>
 *   <li>Golden complete + gate passed -> mark validated (calibration stays).</li>
 *   <li>Golden complete + gate failed -> roll calibration back to the pre-apply thresholds.</li>
 *   <li>Golden not complete after {@code rollback-watch-timeout-ms} -> force rollback (safety).</li>
 * </ul>
 *
 * <p>Pending rows are persisted ({@code calibration_apply_pending}) so a restart doesn't orphan a
 * pending rollback. Polling is a no-op when nothing is pending.
 */
@Component
public class CalibrationRollbackWatcher {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRollbackWatcher.class);

    private final DataSource dataSource;
    private final GoldenEvaluationRunner goldenRunner;
    private final CalibrationState calibrationState;
    private final long timeoutMs;
    private volatile Integer expectedCaseCount;

    public CalibrationRollbackWatcher(DataSource dataSource, GoldenEvaluationRunner goldenRunner,
                                      CalibrationState calibrationState, HarnessRunnerProperties properties) {
        this.dataSource = dataSource;
        this.goldenRunner = goldenRunner;
        this.calibrationState = calibrationState;
        this.timeoutMs = properties.getVerification().getRollbackWatchTimeoutMs();
    }

    /** Register a Golden eval that gates a just-applied threshold tweak. */
    public void registerPending(String evalId, int passBefore, int warnBefore) {
        if (evalId == null) return;
        String sql = "INSERT INTO calibration_apply_pending (eval_id, pass_before, warn_before, status) "
                + "VALUES (?,?,?,'pending') ON DUPLICATE KEY UPDATE "
                + "pass_before=VALUES(pass_before), warn_before=VALUES(warn_before), "
                + "status='pending', started_at=NOW(3), resolved_at=NULL";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, evalId);
            ps.setInt(2, passBefore);
            ps.setInt(3, warnBefore);
            ps.executeUpdate();
            log.info("RollbackWatcher: registered pending eval={} (passBefore={} warnBefore={})",
                    evalId, passBefore, warnBefore);
        } catch (Exception e) {
            log.warn("RollbackWatcher: registerPending failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    @Scheduled(fixedDelayString = "${harness.a2a.verification.rollback-watch-interval-ms:120000}")
    public void pollPending() {
        List<CalibrationApplyPending> pending = loadPending();
        if (pending.isEmpty()) {
            return;
        }
        int expected = expectedCaseCount();
        for (CalibrationApplyPending p : pending) {
            try {
                GoldenEvaluationReport report = goldenRunner.getReport(p.evalId());
                boolean complete = report != null && expected > 0 && report.total() >= expected;
                if (complete) {
                    if (report.gatePassed()) {
                        resolve(p.evalId(), "validated");
                        log.info("RollbackWatcher: eval {} gate passed -> calibration promoted", p.evalId());
                    } else {
                        rollback(p, "gate failed: " + report.gateReason());
                    }
                } else if (System.currentTimeMillis() - p.startedAtMs() > timeoutMs) {
                    rollback(p, "timeout (>" + (timeoutMs / 1000) + "s)");
                }
                // else: Golden still running, revisit next tick.
            } catch (Exception e) {
                log.warn("RollbackWatcher: poll {} failed: {}", p.evalId(), e.getMessage());
            }
        }
    }

    private void rollback(CalibrationApplyPending p, String reason) {
        calibrationState.apply(p.passBefore(), p.warnBefore(), calibrationState.getDirectThreshold(),
                calibrationState.getHintThreshold(), calibrationState.getWData(), calibrationState.getWTool(),
                calibrationState.getWSemantic(), calibrationState.getWAdversarial());
        resolve(p.evalId(), "rolled_back");
        log.warn("RollbackWatcher: rolled back eval {} -> passBefore={} warnBefore={} reason={}",
                p.evalId(), p.passBefore(), p.warnBefore(), reason);
    }

    private void resolve(String evalId, String status) {
        String sql = "UPDATE calibration_apply_pending SET status=?, resolved_at=NOW(3) "
                + "WHERE eval_id=? AND status='pending'";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, evalId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("RollbackWatcher: resolve failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private List<CalibrationApplyPending> loadPending() {
        List<CalibrationApplyPending> out = new ArrayList<>();
        String sql = "SELECT eval_id, pass_before, warn_before, started_at FROM calibration_apply_pending "
                + "WHERE status='pending'";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("started_at");
                out.add(new CalibrationApplyPending(
                        rs.getString("eval_id"),
                        rs.getInt("pass_before"),
                        rs.getInt("warn_before"),
                        ts == null ? 0L : ts.getTime(),
                        "pending"));
            }
        } catch (Exception e) {
            log.warn("RollbackWatcher: loadPending failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private int expectedCaseCount() {
        if (expectedCaseCount != null) return expectedCaseCount;
        String sql = "SELECT COUNT(*) FROM golden_dataset_case";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                expectedCaseCount = rs.getInt(1);
                return expectedCaseCount;
            }
        } catch (Exception e) {
            log.warn("RollbackWatcher: expectedCaseCount failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return 0;
    }
}
