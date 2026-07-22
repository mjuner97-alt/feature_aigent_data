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
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Mutable runtime overlay for trust thresholds / weights (V4.0 §31.2 在线标定). Initialized from the
 * static {@code harness.a2a.verification.trust.*} config, then lazily overridden from the persisted
 * {@code calibration_state} singleton row (so calibration survives restarts). {@link TrustScoreCalculator}
 * reads live values here instead of the immutable config, so {@link TrustCalibrationService#calibrate()}
 * and {@link QualityOptimizationLoop#applyThresholdTweaks()} take effect at runtime without a redeploy.
 */
@Component
public class CalibrationState {

    private static final Logger log = LoggerFactory.getLogger(CalibrationState.class);

    private final DataSource dataSource;
    private final HarnessRunnerProperties.Verification.Trust baseline;

    private volatile int passThreshold;
    private volatile int warnThreshold;
    private volatile int directThreshold;
    private volatile int hintThreshold;
    private volatile double wData;
    private volatile double wTool;
    private volatile double wSemantic;
    private volatile double wAdversarial;
    private volatile boolean loaded = false;

    public CalibrationState(DataSource dataSource, HarnessRunnerProperties properties) {
        this.dataSource = dataSource;
        HarnessRunnerProperties.Verification.Trust t = properties.getVerification().getTrust();
        this.baseline = t;
        this.passThreshold = t.getPassThreshold();
        this.warnThreshold = t.getWarnThreshold();
        this.directThreshold = t.getDirectThreshold();
        this.hintThreshold = t.getHintThreshold();
        this.wData = t.getWData();
        this.wTool = t.getWTool();
        this.wSemantic = t.getWSemantic();
        this.wAdversarial = t.getWAdversarial();
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        String sql = "SELECT pass_threshold, warn_threshold, direct_threshold, hint_threshold, "
                + "w_data, w_tool, w_semantic, w_adversarial FROM calibration_state WHERE id=1";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                passThreshold = rs.getInt("pass_threshold");
                warnThreshold = rs.getInt("warn_threshold");
                directThreshold = rs.getInt("direct_threshold");
                hintThreshold = rs.getInt("hint_threshold");
                wData = rs.getDouble("w_data");
                wTool = rs.getDouble("w_tool");
                wSemantic = rs.getDouble("w_semantic");
                wAdversarial = rs.getDouble("w_adversarial");
                log.info("CalibrationState: loaded persisted calibration pass={} warn={} wData={} wSemantic={}",
                        passThreshold, warnThreshold, wData, wSemantic);
            }
        } catch (Exception e) {
            log.warn("CalibrationState: load persisted failed (using config defaults): {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    /** Apply a new calibrated set and persist (singleton upsert). */
    public synchronized void apply(int pass, int warn, int direct, int hint,
                                   double wd, double wt, double ws, double wa) {
        this.passThreshold = pass;
        this.warnThreshold = warn;
        this.directThreshold = direct;
        this.hintThreshold = hint;
        this.wData = wd;
        this.wTool = wt;
        this.wSemantic = ws;
        this.wAdversarial = wa;
        this.loaded = true;
        persist();
        log.info("CalibrationState: applied pass={} warn={} direct={} hint={} wData={} wSemantic={}",
                pass, warn, direct, hint, wd, ws);
    }

    /** Reset to the static config baseline (rollback). */
    public synchronized void resetToBaseline() {
        apply(baseline.getPassThreshold(), baseline.getWarnThreshold(),
                baseline.getDirectThreshold(), baseline.getHintThreshold(),
                baseline.getWData(), baseline.getWTool(),
                baseline.getWSemantic(), baseline.getWAdversarial());
        log.info("CalibrationState: reset to config baseline");
    }

    private void persist() {
        String sql = "INSERT INTO calibration_state (id, pass_threshold, warn_threshold, direct_threshold, "
                + "hint_threshold, w_data, w_tool, w_semantic, w_adversarial) "
                + "VALUES (1,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "pass_threshold=VALUES(pass_threshold), warn_threshold=VALUES(warn_threshold), "
                + "direct_threshold=VALUES(direct_threshold), hint_threshold=VALUES(hint_threshold), "
                + "w_data=VALUES(w_data), w_tool=VALUES(w_tool), w_semantic=VALUES(w_semantic), "
                + "w_adversarial=VALUES(w_adversarial)";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, 1);
            ps.setInt(2, passThreshold);
            ps.setInt(3, warnThreshold);
            ps.setInt(4, directThreshold);
            ps.setInt(5, hintThreshold);
            ps.setDouble(6, wData);
            ps.setDouble(7, wTool);
            ps.setDouble(8, wSemantic);
            ps.setDouble(9, wAdversarial);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("CalibrationState: persist failed (in-memory only): {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    // ===== live getters (read by TrustScoreCalculator / ResponsePolicyResolver) =====

    public int getPassThreshold() { ensureLoaded(); return passThreshold; }
    public int getWarnThreshold() { ensureLoaded(); return warnThreshold; }
    public int getDirectThreshold() { ensureLoaded(); return directThreshold; }
    public int getHintThreshold() { ensureLoaded(); return hintThreshold; }
    public double getWData() { ensureLoaded(); return wData; }
    public double getWTool() { ensureLoaded(); return wTool; }
    public double getWSemantic() { ensureLoaded(); return wSemantic; }
    public double getWAdversarial() { ensureLoaded(); return wAdversarial; }

    // ===== immutable config baseline (for capping adjustments) =====

    public int baselinePass() { return baseline.getPassThreshold(); }
    public int baselineWarn() { return baseline.getWarnThreshold(); }
    public double baselineWData() { return baseline.getWData(); }
    public double baselineWSemantic() { return baseline.getWSemantic(); }
}
