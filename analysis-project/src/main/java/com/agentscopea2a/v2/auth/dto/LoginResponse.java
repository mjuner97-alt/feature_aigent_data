/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应 DTO - 返回用户身份与所属组织信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String userId;
    private String name;
    private List<String> departments;
    private List<String> statisticsGroups;
    private List<String> productLines;
    private String message;
    /** 是否管理员（app.auth.admin-users 配置的账号，密码校验通过） */
    private boolean admin;

    public LoginResponse(String userId, String name, List<String> departments,
                         List<String> statisticsGroups, List<String> productLines, String message) {
        this(userId, name, departments, statisticsGroups, productLines, message, false);
    }
}
