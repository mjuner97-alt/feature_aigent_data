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

/**
 * 登录请求 DTO - 工号(userId)登录；管理员账号需带 password。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    private String userId;
    /** 仅管理员账号必填；普通用户无需密码 */
    private String password;

    public LoginRequest(String userId) {
        this.userId = userId;
    }
}
