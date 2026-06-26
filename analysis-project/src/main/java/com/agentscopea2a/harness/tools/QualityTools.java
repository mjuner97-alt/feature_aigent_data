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
package com.agentscopea2a.harness.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Map;
import java.util.StringJoiner;

/**
 * 质量数据模拟 Tools，提供按不同维度组合查询质量数据的能力。
 *
 * <p>4 个 Tool 分别对应不同的维度组合：
 * <ul>
 *   <li>部门 + 版本计划</li>
 *   <li>部门 + 季度</li>
 *   <li>部门 + 版本计划 + 应用/组/产品线 + 人</li>
 *   <li>部门 + 季度 + 应用/组/产品线 + 人</li>
 * </ul>
 */
public class QualityTools {

    // ==================== 维度常量 ====================
    //
    // Entity arrays + per-department defect density live in KnownEntities so QualityTools and
    // any future readers share one source (see P2-3 in docs/enhancement-proposal.md).

    private static final String[] DEPARTMENTS = KnownEntities.departmentsArray();

    private static final String[] APPLICATIONS = KnownEntities.applicationsArray();

    private static final String[] TEAMS = KnownEntities.teamsArray();

    private static final String[] PRODUCT_LINES = KnownEntities.productLinesArray();

    private static final String[] PERSONS = KnownEntities.personsArray();

    // 每个部门的版本质量数据（确定性）
    private static final Map<String, Double> DEPARTMENT_VERSION_QUALITY =
            KnownEntities.DEPARTMENT_VERSION_QUALITY;

    // ==================== Tool 1: 部门 + 版本计划 ====================

    @Tool(
            name = "query_quality_by_version_department",
            description =
                    "根据版本计划和部门查询质量数据。返回指定版本下各部门的质量指标（缺陷密度）。" + "当用户问某个版本/月份下哪个部门质量最好/最差时使用此工具。")
    public ToolResultBlock queryByVersionAndDepartment(
            @ToolParam(name = "version_plan", description = "版本计划，格式：xxx年x月份版本，如 2026年4月份版本")
                    String versionPlan,
            @ToolParam(
                            name = "department",
                            description = "部门名称，如：杭州开发一部、杭州开发二部 等。可为 null 表示查询所有部门",
                            required = false)
                    String department) {

        StringJoiner sj = new StringJoiner("\n");
        sj.add("查询条件 - 版本计划：" + versionPlan);
        if (department != null) {
            sj.add("部门：" + department);
        }
        sj.add("");
        sj.add("| 版本计划 | 部门 | 质量分(缺陷密度) |");
        sj.add("|--|--|--|");

        if (department != null) {
            Double quality = DEPARTMENT_VERSION_QUALITY.get(department);
            if (quality != null) {
                sj.add(String.format("| %s | %s | %.1f |", versionPlan, department, quality));
            } else {
                sj.add("未找到该部门的数据");
            }
        } else {
            for (Map.Entry<String, Double> entry : DEPARTMENT_VERSION_QUALITY.entrySet()) {
                sj.add(
                        String.format(
                                "| %s | %s | %.1f |",
                                versionPlan, entry.getKey(), entry.getValue()));
            }
        }

        sj.add("");
        sj.add("说明：质量分越高表示缺陷密度越大，质量越差");
        return ToolResultBlock.text(sj.toString());
    }

    // ==================== Tool 2: 部门 + 季度 ====================

    @Tool(
            name = "query_quality_by_department_quarter",
            description = "根据季度和部门查询质量数据。返回指定季度各部门的质量指标（缺陷密度）。" + "当用户按季度维度查询质量数据时使用此工具。")
    public ToolResultBlock queryByDepartmentAndQuarter(
            @ToolParam(name = "quarter", description = "季度，格式：xxx年x季度，如 2026年1季度") String quarter,
            @ToolParam(
                            name = "department",
                            description = "部门名称，如：杭州开发一部、杭州开发二部 等。可为 null 表示查询所有部门",
                            required = false)
                    String department) {

        int quarterNum = extractQuarterNum(quarter);

        StringJoiner sj = new StringJoiner("\n");
        sj.add("查询条件 - 季度：" + quarter);
        if (department != null) {
            sj.add("部门：" + department);
        }
        sj.add("");
        sj.add("| 季度 | 部门 | 质量分(缺陷密度) |");
        sj.add("|--|--|--|");

        if (department != null) {
            Double base = DEPARTMENT_VERSION_QUALITY.get(department);
            if (base != null) {
                double adjusted = base * quarterFactor(quarterNum);
                sj.add(String.format("| %s | %s | %.1f |", quarter, department, adjusted));
            } else {
                sj.add("未找到该部门的数据");
            }
        } else {
            for (Map.Entry<String, Double> entry : DEPARTMENT_VERSION_QUALITY.entrySet()) {
                double adjusted = entry.getValue() * quarterFactor(quarterNum);
                sj.add(String.format("| %s | %s | %.1f |", quarter, entry.getKey(), adjusted));
            }
        }

        sj.add("");
        sj.add("说明：质量分越高表示缺陷密度越大，质量越差");
        return ToolResultBlock.text(sj.toString());
    }

    // ==================== Tool 3: 部门 + 版本计划 + 应用/组/产品线 + 人 ====================

    @Tool(
            name = "query_quality_by_version_person",
            description =
                    "根据版本计划、部门、应用/组/产品线和人员查询质量数据。"
                            + "支持不同粒度的下钻查询：按应用/组/产品线汇总，或查到具体人员。"
                            + "当用户需要查询某个版本下某部门的应用、组、产品线或人员维度的质量数据时使用此工具。")
    public ToolResultBlock queryByVersionAndPerson(
            @ToolParam(name = "version_plan", description = "版本计划，格式：xxx年x月份版本，如 2026年4月份版本")
                    String versionPlan,
            @ToolParam(name = "department", description = "部门名称，如：杭州开发五部") String department,
            @ToolParam(
                            name = "peer_type",
                            description = "同级维度类型，三选一：APPLICATION(应用)、TEAM(组)、PRODUCT_LINE(产品线)。",
                            required = false)
                    String peerType,
            @ToolParam(
                            name = "peer_name",
                            description =
                                    "同级维度名称，如：F-CMS(应用)、个贷组(组)、信贷产品线(产品线)。"
                                            + "可为 null 表示查询该类型下所有维度",
                            required = false)
                    String peerName,
            @ToolParam(name = "person", description = "人员姓名。可为 null 表示查询该维度下所有人员", required = false)
                    String person) {

        int deptIdx = deptIndex(department);

        StringJoiner sj = new StringJoiner("\n");
        sj.add("查询条件:");
        sj.add("- 版本计划：" + versionPlan);
        sj.add("- 部门：" + department);
        if (peerType != null) {
            sj.add("- " + peerLabel(peerType) + "：" + (peerName != null ? peerName : "全部"));
        }
        if (person != null) {
            sj.add("- 人员：" + person);
        }
        sj.add("");

        if (peerType != null && peerName == null && person == null) {
            // 查该部门下某个维度类型的所有值（如所有应用、所有组）
            String[] peers = getPeerList(peerType);
            String label = peerLabel(peerType);
            sj.add(String.format("该部门下的%s质量数据:", label));
            sj.add(String.format("| %s | 质量分(缺陷密度) |", label));
            sj.add("|--|--|");
            for (int i = 0; i < peers.length; i++) {
                double q = peerScore(deptIdx, i);
                sj.add(String.format("| %s | %.1f |", peers[i], q));
            }
        } else if (peerType != null && peerName != null && person == null) {
            // 查某个应用/组/产品线下所有人员
            String label = peerLabel(peerType);
            sj.add(String.format("| %s | 人员 | 质量分(缺陷密度) |", label));
            sj.add("|--|--|--|");
            for (String p : PERSONS) {
                double q = personScore(deptIdx, peerName, p);
                sj.add(String.format("| %s | %s | %.1f |", peerName, p, q));
            }
        } else if (peerType != null && peerName != null && person != null) {
            // 查具体人员
            double q = personScore(deptIdx, peerName, person);
            String label = peerLabel(peerType);
            sj.add(String.format("| 版本计划 | 部门 | %s | 人员 | 质量分(缺陷密度) |", label));
            sj.add("|--|--|--|--|--|");
            sj.add(
                    String.format(
                            "| %s | %s | %s | %s | %.1f |",
                            versionPlan, department, peerName, person, q));
        } else if (peerType == null && person != null) {
            // 部门级查某个人员（跨所有应用）
            sj.add("| 应用 | 人员 | 质量分(缺陷密度) |");
            sj.add("|--|--|--|");
            for (String app : APPLICATIONS) {
                double q = personScore(deptIdx, app, person);
                sj.add(String.format("| %s | %s | %.1f |", app, person, q));
            }
        } else {
            // peerType==null, person==null → 默认返回该部门下所有应用
            sj.add("该部门下的应用质量数据:");
            sj.add("| 应用 | 质量分(缺陷密度) |");
            sj.add("|--|--|");
            for (int i = 0; i < APPLICATIONS.length; i++) {
                double q = peerScore(deptIdx, i);
                sj.add(String.format("| %s | %.1f |", APPLICATIONS[i], q));
            }
        }

        sj.add("");
        sj.add("说明：质量分越高表示缺陷密度越大，质量越差");
        return ToolResultBlock.text(sj.toString());
    }

    // ==================== Tool 4: 部门 + 季度 + 应用/组/产品线 + 人 ====================

    @Tool(
            name = "query_quality_by_quarter_person",
            description =
                    "根据季度、部门、应用/组/产品线和人员查询质量数据。"
                            + "支持不同粒度的下钻查询：按应用/组/产品线汇总，或查到具体人员。"
                            + "当用户需要按季度查询某部门的应用、组、产品线或人员维度的质量数据时使用此工具。")
    public ToolResultBlock queryByQuarterAndPerson(
            @ToolParam(name = "quarter", description = "季度，格式：xxx年x季度，如 2026年1季度") String quarter,
            @ToolParam(name = "department", description = "部门名称，如：杭州开发五部") String department,
            @ToolParam(
                            name = "peer_type",
                            description = "同级维度类型，三选一：APPLICATION(应用)、TEAM(组)、PRODUCT_LINE(产品线)。",
                            required = false)
                    String peerType,
            @ToolParam(
                            name = "peer_name",
                            description = "同级维度名称，如：F-CMS(应用)、个贷组(组)。" + "可为 null 表示查询该类型下所有维度",
                            required = false)
                    String peerName,
            @ToolParam(name = "person", description = "人员姓名。可为 null 表示查询所有人员", required = false)
                    String person) {

        int deptIdx = deptIndex(department);
        int quarterNum = extractQuarterNum(quarter);
        double qf = quarterFactor(quarterNum);

        StringJoiner sj = new StringJoiner("\n");
        sj.add("查询条件:");
        sj.add("- 季度：" + quarter);
        sj.add("- 部门：" + department);
        if (peerType != null) {
            sj.add("- " + peerLabel(peerType) + "：" + (peerName != null ? peerName : "全部"));
        }
        if (person != null) {
            sj.add("- 人员：" + person);
        }
        sj.add("");

        if (peerType != null && peerName == null && person == null) {
            // 查该部门下某个维度类型的所有值
            String[] peers = getPeerList(peerType);
            String label = peerLabel(peerType);
            sj.add(String.format("该部门下的%s质量数据:", label));
            sj.add(String.format("| %s | 质量分(缺陷密度) |", label));
            sj.add("|--|--|");
            for (int i = 0; i < peers.length; i++) {
                double q = peerScore(deptIdx, i) * qf;
                sj.add(String.format("| %s | %.1f |", peers[i], q));
            }
        } else if (peerType != null && peerName != null && person == null) {
            String label = peerLabel(peerType);
            sj.add(String.format("| %s | 人员 | 质量分(缺陷密度) |", label));
            sj.add("|--|--|--|");
            for (String p : PERSONS) {
                double q = personScore(deptIdx, peerName, p) * qf;
                sj.add(String.format("| %s | %s | %.1f |", peerName, p, q));
            }
        } else if (peerType != null && peerName != null && person != null) {
            double q = personScore(deptIdx, peerName, person) * qf;
            String label = peerLabel(peerType);
            sj.add(String.format("| 季度 | 部门 | %s | 人员 | 质量分(缺陷密度) |", label));
            sj.add("|--|--|--|--|--|");
            sj.add(
                    String.format(
                            "| %s | %s | %s | %s | %.1f |",
                            quarter, department, peerName, person, q));
        } else if (peerType == null && person != null) {
            sj.add("| 应用 | 人员 | 质量分(缺陷密度) |");
            sj.add("|--|--|--|");
            for (String app : APPLICATIONS) {
                double q = personScore(deptIdx, app, person) * qf;
                sj.add(String.format("| %s | %s | %.1f |", app, person, q));
            }
        } else {
            sj.add("该部门下的应用质量数据:");
            sj.add("| 应用 | 质量分(缺陷密度) |");
            sj.add("|--|--|");
            for (int i = 0; i < APPLICATIONS.length; i++) {
                double q = peerScore(deptIdx, i) * qf;
                sj.add(String.format("| %s | %.1f |", APPLICATIONS[i], q));
            }
        }

        sj.add("");
        sj.add("说明：质量分越高表示缺陷密度越大，质量越差");
        return ToolResultBlock.text(sj.toString());
    }

    // ==================== 辅助方法 ====================

    private static String[] getPeerList(String peerType) {
        if (peerType == null) {
            return APPLICATIONS;
        }
        return switch (peerType.toUpperCase()) {
            case "TEAM" -> TEAMS;
            case "APPLICATION" -> APPLICATIONS;
            case "PRODUCT_LINE" -> PRODUCT_LINES;
            default -> APPLICATIONS;
        };
    }

    private static int deptIndex(String department) {
        for (int i = 0; i < DEPARTMENTS.length; i++) {
            if (DEPARTMENTS[i].equals(department)) {
                return i;
            }
        }
        return 0;
    }

    private static String peerLabel(String peerType) {
        return switch (peerType.toUpperCase()) {
            case "TEAM" -> "组";
            case "APPLICATION" -> "应用";
            case "PRODUCT_LINE" -> "产品线";
            default -> peerType;
        };
    }

    /** 同级维度质量分：确定性计算 deptIdx(0-4) * peerIdx(0-4) */
    private static double peerScore(int deptIdx, int peerIdx) {
        return (deptIdx + 1) * (peerIdx + 1) * 1.1;
    }

    /** 人员质量分：基于部门+应用名+人名的确定性计算 */
    private static double personScore(int deptIdx, String peerName, String person) {
        return (deptIdx + 1) * deterministicHash(peerName, person) * 0.8;
    }

    private static double deterministicHash(String a, String b) {
        return Math.abs((a + b).hashCode() % 30) + 1.0;
    }

    private static int extractQuarterNum(String quarter) {
        if (quarter.contains("1季度")) return 1;
        if (quarter.contains("2季度")) return 2;
        if (quarter.contains("3季度")) return 3;
        if (quarter.contains("4季度")) return 4;
        return 1;
    }

    private static double quarterFactor(int quarterNum) {
        return 1 + (quarterNum - 1) * 0.1;
    }
}
