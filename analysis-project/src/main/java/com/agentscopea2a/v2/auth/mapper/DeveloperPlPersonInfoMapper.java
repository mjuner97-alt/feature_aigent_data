/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.mapper;

import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 人员信息 Mapper - 查询 developer_pl_person_info 表。
 * 包路径受 {@code GaussConfig.@MapperScan} 约束。
 */
@Mapper
public interface DeveloperPlPersonInfoMapper {

    /**
     * 根据 user_id(统一认证号)查询人员信息(可能多条:多部门/多统计组)。
     */
    List<DeveloperPlPersonInfo> selectByUserId(@Param("userId") String userId);

    /**
     * 根据 user_id 查询是否存在记录(登录校验)。
     */
    int countByUserId(@Param("userId") String userId);

    /**
     * 查询全部去重的"统计组"值,过滤空串。用于组织维度选择器。
     */
    List<String> selectAllStatisticsGroups();

    /**
     * 查询全部去重的"部门"值,过滤空串。用于组织维度选择器。
     */
    List<String> selectAllDepartments();

    /**
     * 查询全部去重的"产品线"值,过滤空串。用于组织维度选择器。
     */
    List<String> selectAllProductLines();
}
