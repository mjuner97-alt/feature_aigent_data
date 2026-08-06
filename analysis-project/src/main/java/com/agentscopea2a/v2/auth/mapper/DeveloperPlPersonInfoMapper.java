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
     * 根据多个 user_id 批量查询人员信息(列表场景一次性解析姓名,避免 N+1)。
     * 一个 userId 可能返回多条记录(多部门/多统计组),姓名以首条(ORDER BY id)为准。
     * 调用方需保证 userIds 非空。
     */
    List<DeveloperPlPersonInfo> selectByUserIds(@Param("userIds") List<String> userIds);

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

    /**
     * 全部去重的 user_id(过滤空串)。用于 COMPANY 发布场景 / ALL 用户范围。
     */
    List<String> selectAllUserIds();

    /**
     * 按组织维度查 user_id。orgType 取 DEPARTMENT / GROUP / PRODUCT_LINE,
     * 分别匹配 developer_pl_person_info 的 "部门" / "统计组" / "产品线" 列 = orgId。
     * 用于 ORG 用户范围与维度发布命中反查。
     */
    List<String> selectUserIdsByOrg(@Param("orgType") String orgType, @Param("orgId") String orgId);
}
