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

/**
 * Online Trust calibration (V4.0 §31.2 在线标定). Collects human feedback labels on verdicts and,
 * once enough samples accumulate, adjusts the trust thresholds in {@link CalibrationState} to match
 * human judgment - replacing V3.0's static config thresholds.
 *
 * <p>Calibration rules (capped ±{@link #MAX_ADJUST} from the config baseline, min {@link #MIN_SAMPLE}
 * samples):
 * <ul>
 *   <li>PASS verdicts labeled correct &lt; {@link #CORRECTNESS_TARGET} (too many false PASS) ->
 *       raise passThreshold (stricter, fewer PASS).</li>
 *   <li>FAIL verdicts labeled correct &lt; {@link #CORRECTNESS_TARGET} (too many false FAIL) ->
 *       lower warnThreshold (more lenient, fewer FAIL).</li>
 * </ul>
 * Weights are left to a future calibration pass (threshold-only for V4.0 first cut).
 */
@Component
public class TrustCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(TrustCalibrationService.class);
    private static final int MIN_SAMPLE = 10;
    private static final int MAX_ADJUST = 5;
    private static final double CORRECTNESS_TARGET = 0.9;

    private final DataSource dataSource;
    private final CalibrationState calibrationState;

    public TrustCalibrationService(DataSource dataSource, CalibrationState calibrationState) {
        this.dataSource = dataSource;
        this.calibrationState = calibrationState;
    }

    public void recordFeedback(VerificationFeedback fb) {
        if (fb == null || fb.sessionId() == null) return;
        String sql = "INSERT INTO verification_feedback (session_id, verdict, human_label, note, created_by) "
                + "VALUES (?,?,?,?,?)";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fb.sessionId());
            ps.setString(2, fb.verdict());
            ps.setString(3, fb.humanLabel());
            ps.setString(4, fb.note());
            ps.setString(5, fb.createdBy());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("recordFeedback failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public CalibrationReport calibrate() {
        // verdict -> [correctCount, incorrectCount]
        long passCorrect = 0, passIncorrect = 0, warnCorrect = 0, warnIncorrect = 0,
                failCorrect = 0, failIncorrect = 0;
        String sql = "SELECT verdict, human_label, COUNT(*) AS c FROM verification_feedback "
                + "GROUP BY verdict, human_label";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String verdict = rs.getString("verdict");
                String label = rs.getString("human_label");
                long c = rs.getLong("c");
                boolean correct = "correct".equalsIgnoreCase(label);
                if ("pass".equalsIgnoreCase(verdict)) { if (correct) passCorrect += c; else passIncorrect += c; }
                else if ("warn".equalsIgnoreCase(verdict)) { if (correct) warnCorrect += c; else warnIncorrect += c; }
                else if ("fail".equalsIgnoreCase(verdict)) { if (correct) failCorrect += c; else failIncorrect += c; }
            }
        } catch (Exception e) {
            log.warn("calibrate query failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }

        int sampleSize = (int) (passCorrect + passIncorrect + warnCorrect + warnIncorrect + failCorrect + failIncorrect);
        double passRate = rate(passCorrect, passIncorrect);
        double failRate = rate(failCorrect, failIncorrect);
        double warnRate = rate(warnCorrect, warnIncorrect);

        int passBefore = calibrationState.getPassThreshold();
        int warnBefore = calibrationState.getWarnThreshold();
        int direct = calibrationState.getDirectThreshold();
        int hint = calibrationState.getHintThreshold();

        if (sampleSize < MIN_SAMPLE) {
            return new CalibrationReport(sampleSize, passRate, failRate, warnRate,
                    passBefore, passBefore, warnBefore, warnBefore, false,
                    "insufficient samples (<" + MIN_SAMPLE + ")");
        }

        int passAfter = passBefore;
        int warnAfter = warnBefore;
        if (passRate < CORRECTNESS_TARGET) {
            passAfter = Math.min(passBefore + 2, calibrationState.baselinePass() + MAX_ADJUST);
        }
        if (failRate < CORRECTNESS_TARGET) {
            warnAfter = Math.max(warnBefore - 2, calibrationState.baselineWarn() - MAX_ADJUST);
        }
        // sanity: keep pass strictly above warn
        if (passAfter <= warnAfter) {
            passAfter = warnAfter + 5;
        }

        boolean changed = passAfter != passBefore || warnAfter != warnBefore;
        if (changed) {
            calibrationState.apply(passAfter, warnAfter, direct, hint,
                    calibrationState.getWData(), calibrationState.getWTool(),
                    calibrationState.getWSemantic(), calibrationState.getWAdversarial());
            log.info("TrustCalibration: calibrated pass {}->{} warn {}->{} (samples={})",
                    passBefore, passAfter, warnBefore, warnAfter, sampleSize);
        }
        return new CalibrationReport(sampleSize, passRate, failRate, warnRate,
                passBefore, passAfter, warnBefore, warnAfter, changed,
                changed ? "calibrated" : "no change needed");
    }

    private static double rate(long correct, long incorrect) {
        long total = correct + incorrect;
        return total == 0 ? 1.0 : (double) correct / total;
    }
}
