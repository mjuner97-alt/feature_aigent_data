/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.service;

import com.agentscopea2a.v2.auth.dto.LoginRequest;
import com.agentscopea2a.v2.auth.dto.LoginResponse;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 轻量登录服务 - 校验 user_id 在 developer_pl_person_info 表中存在,
 * 返回姓名/部门/统计组/产品线信息。无密码、无 Session、无 Spring Security。
 */
@Service
public class AuthService {

    private final DeveloperPlPersonInfoMapper personInfoMapper;

    public AuthService(DeveloperPlPersonInfoMapper personInfoMapper) {
        this.personInfoMapper = personInfoMapper;
    }

    public LoginResponse login(LoginRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工号不能为空");
        }

        int count = personInfoMapper.countByUserId(userId);
        if (count == 0) {
            throw new IllegalArgumentException("工号不存在,请检查输入");
        }

        List<DeveloperPlPersonInfo> records = personInfoMapper.selectByUserId(userId);
        DeveloperPlPersonInfo first = records.get(0);

        List<String> departments = records.stream()
                .map(DeveloperPlPersonInfo::getDepartment)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> statisticsGroups = records.stream()
                .map(DeveloperPlPersonInfo::getStatisticsGroup)
                .filter(g -> g != null && !g.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> productLines = records.stream()
                .map(DeveloperPlPersonInfo::getProductLine)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .collect(Collectors.toList());

        return new LoginResponse(
                userId,
                first.getName(),
                departments,
                statisticsGroups,
                productLines,
                "登录成功"
        );
    }
}
