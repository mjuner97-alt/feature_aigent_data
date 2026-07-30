/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.entity;

import lombok.Data;

/**
 * 开发PL人员信息实体 - 对应 developer_pl_person_info 表。
 * 一个 userId 可能存在多条记录(多部门/多统计组)。
 */
@Data
public class DeveloperPlPersonInfo {
    private Long id;
    private String userId;
    private String type;
    private String statisticalMonth;
    private String department;
    private String name;
    private String group;
    private String assessmentCount;
    private String adlmGroup;
    private String dept3Group;
    private String dept4Group;
    private String statisticsGroup;
    private String quarter;
    private String versionMonth;
    private String isStatistics;
    private String testCorrespondingDevDept;
    private String testCorrespondingDevGroup;
    private String positionRole;
    private String updateTime;
    private String headOfficeApprovedDept;
    private String subTeam;
    private String testCorrespondingStatGroup;
    private String personnelCategory;
    private String remark;
    private String actualAdminGroup;
    private String testGroup;
    private String updater;
    private String remarkRms;
    private String linkedId;
    private String isDesignBackbone;
    private String productLine;
}
