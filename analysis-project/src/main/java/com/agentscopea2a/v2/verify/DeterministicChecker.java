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
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic Rule Engine (V3.0 design §7, recovery-aware). Free, zero-hallucination, assertable.
 *
 * <p>Splits event-stream findings into:
 * <ul>
 *   <li><b>Class A (process)</b> - intermediate errors that ReAct trial-and-error normally recovers
 *       from. Recorded as {@code info} findings (recovery-aware), never a hard fail.</li>
 *   <li><b>Class B (conclusion)</b> - terminal, unrecoverable:
 *     <ul>
 *       <li><b>B1</b> candidate conclusion references an artifact path that was never produced.</li>
 *       <li><b>B2</b> conclusion cites numbers but the trace has no successful data-producing call.</li>
 *       <li><b>B4</b> contract aggregation violation (from {@link ContractComplianceChecker}).</li>
 *     </ul>
 *     A Class B hit short-circuits the LLM and dispatches a typed repair (REFUSE for fabrication,
 *     SEMANTIC_FIX for contract).</li>
 * </ul>
 */
@Component
public class DeterministicChecker {

    private final ContractComplianceChecker contractChecker;
    private final ToolRoutersIndex toolRoutersIndex;
    private final HarnessRunnerProperties.Verification config;

    /** Tools whose successful output counts as "data produced" (for B2 fabrication check). */
    private static final Set<String> DATA_TOOLS = Set.of(
            "router_tool", "data_aggregate", "data_top_n", "data_compare_ratio",
            "data_pivot", "data_distribution",
            "quality_query_by_version_department", "quality_query_by_department_quarter",
            "quality_query_by_version_person", "quality_query_by_quarter_person");

    private static final Pattern CSV_PATH = Pattern.compile("[\\w/\\-]+\\.csv");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final Pattern ERROR_MARKER =
            Pattern.compile("error|错误|失败|未知|未找到|exception|未知的 ?toolid", Pattern.CASE_INSENSITIVE);

    /** Arithmetic pattern (digit-operator-digit, incl. Unicode × ÷) for mental-math detection. */
    private static final Pattern ARITH_PATTERN =
            Pattern.compile("\\d[\\d.]*\\s*[+\\-*/\\u00D7\\u00F7%]\\s*\\d[\\d.]*");

    public DeterministicChecker(ContractComplianceChecker contractChecker,
                                ToolRoutersIndex toolRoutersIndex,
                                HarnessRunnerProperties properties) {
        this.contractChecker = contractChecker;
        this.toolRoutersIndex = toolRoutersIndex;
        this.config = properties.getVerification();
    }

    public Result check(VerificationContext vctx) {
        Result result = new Result();
        String conclusion = vctx.getCandidateConclusion();

        // B4: contract compliance (aggregation hard-fail, direction advisory).
        List<ContractComplianceChecker.ContractViolation> cv =
                contractChecker.check(conclusion, vctx.getContractSnapshot());
        result.contractViolations.addAll(cv);
        if (contractChecker.hasHardFail(cv)) {
            result.markClassB("B4", "契约聚合违规: " + firstHardFail(cv));
        }

        if (conclusion != null && !conclusion.isBlank()) {
            // B1: artifact path terminal consistency.
            if (config.getDeterministic().isArtifactPath()) {
                List<String> referenced = extractCsvPaths(conclusion);
                List<String> produced = vctx.getArtifactAgentPaths();
                for (String p : referenced) {
                    if (!produced.contains(p)) {
                        result.markClassB("B1", "结论引用了未产出的 artifact 路径: " + p);
                        result.findings.add(new Finding("data", "fail",
                                "artifact 路径终态不一致: " + p, "conclusion-ref vs artifactRefs", false));
                        break;
                    }
                }
            }
            // B2: fabrication from nothing - numbers in conclusion but no successful data call.
            if (!result.classBFatal && config.getDeterministic().isFabricationFromNothing()
                    && HAS_DIGIT.matcher(conclusion).find() && !hasSuccessfulDataCall(vctx)) {
                result.markClassB("B2", "结论含数字但全链路无成功数据产出(无中生有)");
            }
        }

        // Class A: process errors (recovery-aware, info only).
        scanProcessErrors(vctx, result);

        // Compliance: mental-math detection (arith in conclusion but no arith tool call this turn).
        scanArithMentalMath(vctx, result);

        return result;
    }

    private void scanArithMentalMath(VerificationContext vctx, Result result) {
        if (!config.getDeterministic().isArithMentalMath()) return;
        String conclusion = vctx.getCandidateConclusion();
        if (conclusion == null || conclusion.isBlank()) return;
        if (!ARITH_PATTERN.matcher(conclusion).find()) return;
        boolean arithCalled = vctx.getEventStream().stream().anyMatch(e ->
                AgentExecutionEvent.TOOL_CALL_STARTED.equals(e.type())
                        && e.payload() != null
                        && "arith".equalsIgnoreCase(String.valueOf(e.payload().get("tool"))));
        if (!arithCalled) {
            result.findings.add(new Finding("tool", "warn",
                    "心算检测: 结论含算术表达式但本轮未调用 arith 工具",
                    "conclusion arith pattern + no arith tool call", false));
        }
    }

    private boolean hasSuccessfulDataCall(VerificationContext vctx) {
        for (AgentExecutionEvent e : vctx.getEventStream()) {
            if (!AgentExecutionEvent.TOOL_CALL_COMPLETED.equals(e.type())) continue;
            Object tool = e.payload() == null ? null : e.payload().get("tool");
            Object isError = e.payload() == null ? null : e.payload().get("isError");
            Object isEmpty = e.payload() == null ? null : e.payload().get("isEmpty");
            if (tool instanceof String t && DATA_TOOLS.contains(t)
                    && !Boolean.TRUE.equals(isError) && !Boolean.TRUE.equals(isEmpty)) {
                return true;
            }
        }
        return false;
    }

    private void scanProcessErrors(VerificationContext vctx, Result result) {
        boolean recovered = hasSuccessfulDataCall(vctx); // simple recovery heuristic
        for (AgentExecutionEvent e : vctx.getEventStream()) {
            if (!AgentExecutionEvent.TOOL_CALL_COMPLETED.equals(e.type())) continue;
            Object output = e.payload() == null ? null : e.payload().get("output");
            if (output instanceof String s && ERROR_MARKER.matcher(s).find()) {
                result.findings.add(new Finding("tool", "info",
                        "过程类错误(已恢复=" + recovered + "): " + truncate(s, 120),
                        e.eventId(), recovered));
            }
            // unknown toolId (router_tool) - Class A info.
            Object tool = e.payload() == null ? null : e.payload().get("tool");
            if (tool instanceof String s && s.contains("未知的 toolId")) {
                result.findings.add(new Finding("tool", "info",
                        "未知 toolId(已恢复=" + recovered + ")", e.eventId(), recovered));
            }
        }
    }

    private List<String> extractCsvPaths(String text) {
        List<String> paths = new ArrayList<>();
        java.util.regex.Matcher m = CSV_PATH.matcher(text);
        while (m.find()) {
            paths.add(m.group());
        }
        return paths;
    }

    private String firstHardFail(List<ContractComplianceChecker.ContractViolation> cv) {
        for (ContractComplianceChecker.ContractViolation v : cv) {
            if (v.hardFail()) return v.description();
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== Result =====

    public static class Result {
        private boolean classBFatal;
        private String failCode; // B1 / B2 / B4
        private String failReason;
        private final List<Finding> findings = new ArrayList<>();
        private final List<ContractComplianceChecker.ContractViolation> contractViolations = new ArrayList<>();

        public void markClassB(String code, String reason) {
            this.classBFatal = true;
            this.failCode = code;
            this.failReason = reason;
        }

        public boolean isClassBFatal() { return classBFatal; }
        public String getFailCode() { return failCode; }
        public String getFailReason() { return failReason; }
        public List<Finding> getFindings() { return findings; }
        public List<ContractComplianceChecker.ContractViolation> getContractViolations() { return contractViolations; }

        /** One-line precheck report for the verify.md prompt. */
        public String toReport() {
            if (classBFatal) {
                return "确定性预检 HARD FAIL [" + failCode + "]: " + failReason;
            }
            if (findings.isEmpty()) {
                return "确定性预检: 通过(无结论类硬错误)";
            }
            return "确定性预检: " + findings.size() + " 处过程类 info(恢复感知), 无结论类硬错误";
        }
    }

    public record Finding(String dimension, String severity, String description, String evidence, boolean recovered) {
    }
}
