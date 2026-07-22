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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto quality-optimization closed loop (V3.0 design §26, P2 - lightweight). Aggregates high-frequency
 * FAIL patterns from {@code verification_record} / {@code repair_execution_history} and distills
 * <b>candidate</b> proposals: new deterministic rules / Semantic-Contract supplements, capability-
 * boundary marks, or threshold/weight tweaks.
 *
 * <p><b>No auto-apply</b> (V3.0 §26.3): proposals are logged and returned for human review. Weight /
 * threshold changes additionally require a Golden regression pass to prevent "relax thresholds to
 * inflate scores".
 */
@Component
public class QualityOptimizationLoop {

    private static final Logger log = LoggerFactory.getLogger(QualityOptimizationLoop.class);
    private static final int RULE_CANDIDATE_THRESHOLD = 3;
    private static final int LOW_DIMENSION_THRESHOLD = 70;
    private static final int MAX_ADJUST = 5;

    private final DataSource dataSource;
    private final CalibrationState calibrationState;
    private final GoldenEvaluationRunner goldenRunner;
    private final CalibrationRollbackWatcher rollbackWatcher;

    public QualityOptimizationLoop(DataSource dataSource, CalibrationState calibrationState,
                                   GoldenEvaluationRunner goldenRunner,
                                   CalibrationRollbackWatcher rollbackWatcher) {
        this.dataSource = dataSource;
        this.calibrationState = calibrationState;
        this.goldenRunner = goldenRunner;
        this.rollbackWatcher = rollbackWatcher;
    }

    public List<OptimizationProposal> runOptimization() {
        List<OptimizationProposal> proposals = new ArrayList<>();

        // 1. High-frequency repair types -> distill into deterministic rule / contract supplement.
        Map<String, Integer> repairCounts = countByRepairType();
        repairCounts.forEach((rt, cnt) -> {
            if (cnt >= RULE_CANDIDATE_THRESHOLD) {
                proposals.add(new OptimizationProposal("RULE_CANDIDATE",
                        "高频修复类型 " + rt + " (" + cnt + " 次): 建议蒸馏为确定性规则 / 补全 Semantic Contract(人工审核入库)",
                        "repair_type=" + rt, cnt));
            }
        });

        // 2. Gaming suspects -> mark capability boundary / feed skill补全.
        int gaming = countGamingSuspects();
        if (gaming > 0) {
            proposals.add(new OptimizationProposal("CAPABILITY_BOUNDARY",
                    "检测到 " + gaming + " 次 gaming 嫌疑(改措辞过校验): 建议标记为能力边界, 反哺技能/Skill 补全",
                    "gaming_suspect=1", gaming));
        }

        // 3. Low-average dimensions -> threshold / weight tweak (requires Golden regression).
        Map<String, Double> avgDims = avgFailDimensions();
        avgDims.forEach((dim, avg) -> {
            if (avg != null && avg < LOW_DIMENSION_THRESHOLD) {
                proposals.add(new OptimizationProposal("THRESHOLD_TUNE",
                        "维度 " + dim + " FAIL 平均分 " + String.format("%.1f", avg) + " 偏低: 建议复核阈值/权重(需 Golden 回归通过)",
                        "dim=" + dim, (int) Math.round(avg)));
            }
        });

        for (OptimizationProposal p : proposals) {
            log.info("QualityOptimization proposal: {} | {} | {}", p.type(), p.evidence(), p.description());
        }
        log.info("QualityOptimizationLoop: produced {} proposal(s) (no auto-apply)", proposals.size());
        return proposals;
    }

    /**
     * V4.0 auto-apply (完整自动优化闭环): apply the THRESHOLD_TUNE proposals (capped) to
     * {@link CalibrationState} and trigger a Golden evaluation to gate the change. Returns the
     * before/after thresholds + the Golden eval id (for {@link #checkAutoRollback} to validate /
     * roll back). Weights are not auto-tuned (threshold-only for V4.0 first cut).
     */
    public OptimizationApplyResult applyThresholdTweaks(String releasedBy) {
        List<OptimizationProposal> proposals = runOptimization();
        int passBefore = calibrationState.getPassThreshold();
        int warnBefore = calibrationState.getWarnThreshold();
        int passAfter = passBefore;
        int warnAfter = warnBefore;
        int applied = 0;
        for (OptimizationProposal p : proposals) {
            if (!"THRESHOLD_TUNE".equals(p.type())) continue;
            String dim = extractDim(p.evidence());
            if (dim == null) continue;
            if ("semantic".equals(dim)) {
                passAfter = Math.min(passAfter + 1, calibrationState.baselinePass() + MAX_ADJUST);
                applied++;
            } else if ("data".equals(dim)) {
                warnAfter = Math.max(warnAfter - 1, calibrationState.baselineWarn() - MAX_ADJUST);
                applied++;
            }
        }
        boolean changed = passAfter != passBefore || warnAfter != warnBefore;
        String evalId = null;
        if (changed) {
            if (passAfter <= warnAfter) passAfter = warnAfter + 5;
            calibrationState.apply(passAfter, warnAfter, calibrationState.getDirectThreshold(),
                    calibrationState.getHintThreshold(), calibrationState.getWData(), calibrationState.getWTool(),
                    calibrationState.getWSemantic(), calibrationState.getWAdversarial());
            evalId = goldenRunner.startEvaluation("auto-opt-" + System.currentTimeMillis(),
                    "v1", null, null, null, releasedBy);
            rollbackWatcher.registerPending(evalId, passBefore, warnBefore);
            log.info("QualityOptimization auto-apply: pass {}->{} warn {}->{} golden={} (rollback-watch armed)",
                    passBefore, passAfter, warnBefore, warnAfter, evalId);
        }
        return new OptimizationApplyResult(applied, passBefore, passAfter, warnBefore, warnAfter,
                evalId, false, changed ? "applied + golden triggered" : "no threshold change");
    }

    /** V4.0 auto-rollback: if the given Golden eval failed its gate, reset calibration to baseline. */
    public boolean checkAutoRollback(String evalId) {
        if (evalId == null) return false;
        GoldenEvaluationReport report = goldenRunner.getReport(evalId);
        if (report == null) return false; // eval not finished yet
        if (!report.gatePassed()) {
            calibrationState.resetToBaseline();
            log.warn("QualityOptimization auto-rollback: golden {} gate failed -> reset to baseline. reason={}",
                    evalId, report.gateReason());
            return true;
        }
        return false;
    }

    /** Manual rollback: reset calibration to the static config baseline. */
    public void rollbackToBaseline() {
        calibrationState.resetToBaseline();
    }

    private static String extractDim(String evidence) {
        if (evidence == null) return null;
        int idx = evidence.indexOf("dim=");
        if (idx < 0) return null;
        return evidence.substring(idx + 4).trim();
    }

    private Map<String, Integer> countByRepairType() {
        Map<String, Integer> out = new LinkedHashMap<>();
        String sql = "SELECT repair_type, COUNT(*) AS c FROM verification_record "
                + "WHERE verdict='fail' AND repair_type IS NOT NULL GROUP BY repair_type ORDER BY c DESC";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("repair_type"), rs.getInt("c"));
            }
        } catch (Exception e) {
            log.warn("countByRepairType failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private int countGamingSuspects() {
        String sql = "SELECT COUNT(*) FROM repair_execution_history WHERE gaming_suspect=1";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.warn("countGamingSuspects failed: {}", e.getMessage());
            return 0;
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private Map<String, Double> avgFailDimensions() {
        Map<String, Double> out = new LinkedHashMap<>();
        String sql = "SELECT AVG(dim_tool) t, AVG(dim_data) d, AVG(dim_semantic) s, AVG(dim_adversarial) a "
                + "FROM verification_record WHERE verdict='fail'";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                out.put("tool", rs.getObject("t") == null ? null : rs.getDouble("t"));
                out.put("data", rs.getObject("d") == null ? null : rs.getDouble("d"));
                out.put("semantic", rs.getObject("s") == null ? null : rs.getDouble("s"));
                out.put("adversarial", rs.getObject("a") == null ? null : rs.getDouble("a"));
            }
        } catch (Exception e) {
            log.warn("avgFailDimensions failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }
}
