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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.dto.UserInfo;
import com.agentscopea2a.v2.service.MockOrgService;
import org.springframework.web.bind.annotation.*;

/**
 * 组织信息 REST 接口:提供用户四维归属查询,供前端展示测试身份归属。
 */
@RestController
@RequestMapping("/api/org")
@CrossOrigin(origins = "*", maxAge = 3600)
public class OrgController {

    private final MockOrgService mockOrgService;

    public OrgController(MockOrgService mockOrgService) {
        this.mockOrgService = mockOrgService;
    }

    @GetMapping("/user-info")
    public UserInfo userInfo(@RequestParam("userId") String userId) {
        return mockOrgService.getUserInfo(userId);
    }
}
