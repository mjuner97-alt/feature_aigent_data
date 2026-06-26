/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.agent.tools;

import com.agentscopea2a.harness.tools.QualityTools;
import com.agentscopea2a.harness.tools.ToolRoutersIndex;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 通用 Agent 工具集 —— 提供其它 Tool 类未覆盖的杂项能力。
 *
 * <p>本类作为新工具入口,具体业务方法在此扩展。注册为 Spring Bean,以便统一通过
 * {@link ToolRoutersIndex} 路由调用。
 */
@Component
public class AgentTools {

    private final QualityTools qualityTools = new QualityTools();

    private void sleepForLongToolReturn() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工具等待被中断", e);
        }
    }

    @Tool(
            name = "quality_query_by_version_department",
            description = "根据版本计划和部门查询质量数据。返回指定版本下各部门的质量指标（缺陷密度）。当用户问某个版本/月度下哪个部门质量最好/最差时使用。")
    public ToolResultBlock queryQualityByVersionDepartment(
            @ToolParam(name = "version_plan", description = "版本计划，格式：xxx年x月份版本，如 2026年4月份版本")
                    String versionPlan,
            @ToolParam(
                            name = "department",
                            description = "部门名称，如：杭州开发一部、杭州开发二部。可不传，表示查询所有部门",
                            required = false)
                    String department) {
        sleepForLongToolReturn();
        return qualityTools.queryByVersionAndDepartment(versionPlan, department);
    }

    @Tool(
            name = "quality_query_by_department_quarter",
            description = "根据季度和部门查询质量数据。返回指定季度各部门的质量指标（缺陷密度）。当用户按季度维度查询质量数据时使用。")
    public ToolResultBlock queryQualityByDepartmentQuarter(
            @ToolParam(name = "quarter", description = "季度，格式：xxx年x季度，如 2026年1季度") String quarter,
            @ToolParam(
                            name = "department",
                            description = "部门名称，如：杭州开发一部、杭州开发二部。可不传，表示查询所有部门",
                            required = false)
                    String department) {
        sleepForLongToolReturn();
        return qualityTools.queryByDepartmentAndQuarter(quarter, department);
    }

    @Tool(
            name = "quality_query_by_version_person",
            description = "根据版本计划、部门、应用/组/产品线和人员查询质量数据。支持按应用、组、产品线汇总，或查到具体人员。")
    public ToolResultBlock queryQualityByVersionPerson(
            @ToolParam(name = "version_plan", description = "版本计划，格式：xxx年x月份版本，如 2026年4月份版本")
                    String versionPlan,
            @ToolParam(name = "department", description = "部门名称，如：杭州开发五部") String department,
            @ToolParam(
                            name = "peer_type",
                            description = "同级维度类型，三选一：APPLICATION(应用)、TEAM(组)、PRODUCT_LINE(产品线)",
                            required = false)
                    String peerType,
            @ToolParam(
                            name = "peer_name",
                            description = "同级维度名称，如：F-CMS(应用)、个贷组(组)、信贷产品线(产品线)。可不传，表示查询该类型下所有维度",
                            required = false)
                    String peerName,
            @ToolParam(name = "person", description = "人员姓名。可不传，表示查询该维度下所有人员", required = false)
                    String person) {
        sleepForLongToolReturn();
        return qualityTools.queryByVersionAndPerson(versionPlan, department, peerType, peerName, person);
    }

    @Tool(
            name = "quality_query_by_quarter_person",
            description = "根据季度、部门、应用/组/产品线和人员查询质量数据。支持按应用、组、产品线汇总，或查到具体人员。")
    public ToolResultBlock queryQualityByQuarterPerson(
            @ToolParam(name = "quarter", description = "季度，格式：xxx年x季度，如 2026年1季度") String quarter,
            @ToolParam(name = "department", description = "部门名称，如：杭州开发五部") String department,
            @ToolParam(
                            name = "peer_type",
                            description = "同级维度类型，三选一：APPLICATION(应用)、TEAM(组)、PRODUCT_LINE(产品线)",
                            required = false)
                    String peerType,
            @ToolParam(
                            name = "peer_name",
                            description = "同级维度名称，如：F-CMS(应用)、个贷组(组)、信贷产品线(产品线)。可不传，表示查询该类型下所有维度",
                            required = false)
                    String peerName,
            @ToolParam(name = "person", description = "人员姓名。可不传，表示查询该维度下所有人员", required = false)
                    String person) {
        sleepForLongToolReturn();
        return qualityTools.queryByQuarterAndPerson(quarter, department, peerType, peerName, person);
    }
}
