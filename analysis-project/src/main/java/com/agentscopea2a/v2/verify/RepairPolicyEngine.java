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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Repair Policy Engine (V3.0 design §10/§12, P0). Sits between the Verifier and the business agent:
 * instead of letting the agent freely rewrite its answer to pass verification (the "rephrase to
 * pass" gaming pattern), it matches the failure to a typed {@link RepairType} via a policy table
 * and emits a concrete directive.
 *
 * <p><b>Anti-gaming</b>: {@code MODIFY_RESULT} / {@code CHANGE_CONCLUSION} are not in the
 * {@link RepairType} enum and are in every rule's {@code forbidden} list + a global forbid set, so
 * they can never be dispatched. The agent can only re-query data, fix reasoning/parameters, ask the
 * user, or refuse - never silently rephrase.
 *
 * <p>Phase 1 loads rules from the {@code repair_policy_rule} table (seeded by Flyway). The
 * {@code repair.policy-path} YAML override is reserved for a future hot-reload path.
 */
@Component
public class RepairPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RepairPolicyEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Global forbid set - never dispatched regardless of rule. */
    private static final Set<String> GLOBAL_FORBID = Set.of("MODIFY_RESULT", "CHANGE_CONCLUSION");

    private final DataSource dataSource;
    private final HarnessRunnerProperties.Verification config;

    private volatile List<RepairPolicyRule> rulesCache = new CopyOnWriteArrayList<>();
    private volatile long loadedAt = 0L;

    public RepairPolicyEngine(DataSource dataSource, HarnessRunnerProperties properties) {
        this.dataSource = dataSource;
        this.config = properties.getVerification();
    }

    /**
     * Decide the repair plan for a failed verdict. Rules-first (Class B / contract -> fixed type),
     * then the LLM's {@code repairHint}, then a light LLM-style fallback classification.
     */
    public RepairPlan plan(VerificationVerdict verdict,
                           DeterministicChecker.Result precheck,
                           VerificationContext vctx) {
        if (verdict == null || verdict.isPass()) {
            return RepairPlan.none();
        }

        String errorType = classify(verdict, precheck);
        String severity = severityOf(errorType, verdict);

        RepairPolicyRule rule = match(errorType, severity);
        RepairType action = rule != null ? rule.firstAllowed() : RepairType.REFUSE;

        // Anti-gaming: never dispatch a globally-forbidden action.
        if (isForbidden(action, rule)) {
            log.warn("RepairPolicyEngine: action {} forbidden for errorType={} -> downgrading to REFUSE",
                    action, errorType);
            action = RepairType.REFUSE;
        }

        int maxRetry = rule != null ? rule.maxRetry() : 0;
        String directive = buildDirective(action, verdict, precheck);

        log.info("RepairPolicyEngine: errorType={} severity={} -> action={} maxRetry={}",
                errorType, severity, action, maxRetry);
        return new RepairPlan(action, directive, maxRetry, errorType, severity);
    }

    // -------- classification (rules-first) --------

    private String classify(VerificationVerdict verdict, DeterministicChecker.Result precheck) {
        if (precheck != null && precheck.isClassBFatal()) {
            String code = precheck.getFailCode();
            if ("B2".equals(code)) return "FABRICATION";
            if ("B4".equals(code)) return "SEMANTIC_MISMATCH";
            if ("B1".equals(code)) return "DATA_MISSING"; // referenced path not produced -> re-query
        }
        // LLM hint from verify.md
        RepairType hint = verdict.getRepairType();
        if (hint == RepairType.DATA_REQUERY) return "DATA_MISSING";
        if (hint == RepairType.SEMANTIC_FIX) {
            // distinguish arithmetic vs semantic by data-dimension issues
            return hasArithmeticIssue(verdict) ? "ARITHMETIC_ERROR" : "SEMANTIC_MISMATCH";
        }
        if (hint == RepairType.PARAMETER_FIX) return "PARAMETER_ERROR";
        if (hint == RepairType.CLARIFY_USER) return "QUESTION_AMBIGUOUS";
        if (hint == RepairType.REFUSE) return "FABRICATION";

        // Fallback: infer from which dimension failed.
        if (verdict.getData() != null && verdict.getData().isFail()) {
            return hasArithmeticIssue(verdict) ? "ARITHMETIC_ERROR" : "DATA_MISSING";
        }
        if (verdict.getConclusion() != null && verdict.getConclusion().isFail()) {
            return "SEMANTIC_MISMATCH";
        }
        if (verdict.getToolCalls() != null && verdict.getToolCalls().isFail()) {
            return "PARAMETER_ERROR";
        }
        return "SEMANTIC_MISMATCH";
    }

    private boolean hasArithmeticIssue(VerificationVerdict v) {
        if (v.getData() == null || v.getData().getIssues() == null) return false;
        for (VerificationVerdict.Issue i : v.getData().getIssues()) {
            String d = i.getDescription() == null ? "" : i.getDescription();
            if (d.contains("取整") || d.contains("算术") || d.contains("不一致")) return true;
        }
        return false;
    }

    private String severityOf(String errorType, VerificationVerdict v) {
        if ("FABRICATION".equals(errorType)) return "CRITICAL";
        if ("SEMANTIC_MISMATCH".equals(errorType) || "DATA_MISSING".equals(errorType)) return "HIGH";
        return "MEDIUM";
    }

    private boolean isForbidden(RepairType action, RepairPolicyRule rule) {
        if (!config.getRepair().isForbidModifyResult()) return false;
        // MODIFY_RESULT / CHANGE_CONCLUSION aren't enum members, so action can't be them -
        // this is belt-and-suspenders for any future taxonomy extension.
        if (action == RepairType.REFUSE) return false;
        if (rule != null && rule.forbids(action)) return true;
        return false;
    }

    private String buildDirective(RepairType action, VerificationVerdict verdict, DeterministicChecker.Result precheck) {
        String corrections = verdict.getCorrections() == null || verdict.getCorrections().isEmpty()
                ? (verdict.getSummary() == null ? "" : verdict.getSummary())
                : String.join("; ", verdict.getCorrections());
        String precheckNote = precheck != null && precheck.isClassBFatal()
                ? "[" + precheck.getFailCode() + "] " + precheck.getFailReason() + ". " : "";
        return switch (action) {
            case DATA_REQUERY -> precheckNote + "重新取数(补查缺失维度/时间范围, 不得改措辞): " + corrections;
            case SEMANTIC_FIX -> precheckNote + "基于 Semantic Contract 修正推理(严禁改数据/改措辞过校验): " + corrections;
            case PARAMETER_FIX -> precheckNote + "修正参数(时间范围/实体名)后重取: " + corrections;
            case CLARIFY_USER -> "向用户澄清问题(暂停闭环): " + corrections;
            case REFUSE -> precheckNote + "如实拒绝/说明数据不足(严禁编造): " + corrections;
            case NONE -> "";
        };
    }

    // -------- rule matching / loading --------

    private RepairPolicyRule match(String errorType, String severity) {
        ensureLoaded();
        RepairPolicyRule best = null;
        for (RepairPolicyRule r : rulesCache) {
            if (!r.enabled()) continue;
            if (!r.errorType().equalsIgnoreCase(errorType)) continue;
            if (r.severity().equalsIgnoreCase(severity)) return r;
            if (best == null) best = r; // fall back to same errorType, different severity
        }
        return best;
    }

    private void ensureLoaded() {
        long ttlMs = config.getContract().getCacheTtlSeconds() * 1000L;
        long now = System.currentTimeMillis();
        if (loadedAt != 0L && (now - loadedAt) < ttlMs) return;
        synchronized (this) {
            if (loadedAt != 0L && (System.currentTimeMillis() - loadedAt) < ttlMs) return;
            try {
                List<RepairPolicyRule> loaded = loadFromYaml();
                String source = "yaml";
                if (loaded == null || loaded.isEmpty()) {
                    loaded = loadRulesFromDb();
                    source = "db";
                }
                rulesCache = new CopyOnWriteArrayList<>(loaded);
                loadedAt = System.currentTimeMillis();
                log.info("RepairPolicyEngine: loaded {} rules from {}", rulesCache.size(), source);
            } catch (Exception e) {
                log.warn("RepairPolicyEngine: load failed (using {} cached): {}",
                        rulesCache.size(), e.getMessage());
                loadedAt = System.currentTimeMillis();
            }
        }
    }

    private List<RepairPolicyRule> loadRulesFromDb() {
        String sql = "SELECT rule_id, error_type, severity, allowed_actions_json, forbidden_json, "
                + "max_retry, priority, enabled FROM repair_policy_rule WHERE enabled=1 ORDER BY priority DESC";
        List<RepairPolicyRule> out = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new RepairPolicyRule(
                        rs.getString("rule_id"),
                        rs.getString("error_type"),
                        rs.getString("severity"),
                        readList(rs.getString("allowed_actions_json")),
                        readList(rs.getString("forbidden_json")),
                        rs.getInt("max_retry"),
                        rs.getInt("priority"),
                        rs.getBoolean("enabled")));
            }
        } catch (Exception e) {
            log.warn("RepairPolicyEngine loadRulesFromDb failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    /**
     * Load from the YAML resource (V3.0 design §10.3 - hot-reloadable via TTL). Empty list when the
     * resource is absent or unreadable, so {@link #ensureLoaded} falls back to the DB table.
     */
    @SuppressWarnings("unchecked")
    private List<RepairPolicyRule> loadFromYaml() {
        try (InputStream in = getClass().getResourceAsStream("/workspace/policies/repair_policy.yaml")) {
            if (in == null) return List.of();
            Map<String, Object> root = new Yaml().load(in);
            Object policiesObj = root == null ? null : root.get("policies");
            if (!(policiesObj instanceof List<?> policies)) return List.of();
            List<RepairPolicyRule> out = new ArrayList<>();
            int prio = 100;
            for (Object o : policies) {
                if (!(o instanceof Map<?, ?> p)) continue;
                String errorType = str(p.get("error_type"));
                if (errorType == null) continue;
                out.add(new RepairPolicyRule(
                        "yaml-" + errorType.toLowerCase(),
                        errorType,
                        str(p.get("severity")),
                        strList(p.get("allowed_actions")),
                        strList(p.get("forbidden")),
                        intVal(p.get("max_retry"), 1),
                        prio--,
                        true));
            }
            return out;
        } catch (Exception e) {
            log.warn("RepairPolicyEngine loadFromYaml failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    private static int intVal(Object o, int def) {
        return (o instanceof Number n) ? n.intValue() : def;
    }

    @SuppressWarnings("unchecked")
    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
