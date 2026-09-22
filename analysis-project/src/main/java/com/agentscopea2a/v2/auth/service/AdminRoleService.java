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
package com.agentscopea2a.v2.auth.service;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 管理员角色判定（内网信任模型：密码只在登录时校验，登录后请求凭 X-User-Id 头识别）。
 *
 * <p>配置格式 {@code app.auth.admin-users=userId:password,userId2:password2}；
 * 空配置 = 无管理员。密码含逗号/冒号时用环境变量展开整对值。
 */
@Component
public class AdminRoleService {

    private final String rawConfig;

    private Map<String, String> credentials = Map.of();

    public AdminRoleService(@Value("${app.auth.admin-users:}") String rawConfig) {
        this.rawConfig = rawConfig == null ? "" : rawConfig;
        parse();
    }

    private void parse() {
        Map<String, String> parsed = new HashMap<>();
        for (String pair : rawConfig.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int sep = entry.indexOf(':');
            if (sep <= 0 || sep == entry.length() - 1) {
                continue;
            }
            parsed.put(entry.substring(0, sep).trim().toLowerCase(Locale.ROOT),
                    entry.substring(sep + 1).trim());
        }
        this.credentials = Map.copyOf(parsed);
    }

    public boolean isAdminUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return credentials.containsKey(userId.trim().toLowerCase(Locale.ROOT));
    }

    public boolean matchesPassword(String userId, String rawPassword) {
        if (userId == null || userId.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        String expected = credentials.get(userId.trim().toLowerCase(Locale.ROOT));
        return expected != null && expected.equals(rawPassword);
    }
}
