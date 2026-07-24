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

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由 fallback。Vue 使用 createWebHistory(history 模式),直接访问或刷新
 * {@code /chat} {@code /dashboard} {@code /skills} 等客户端路由时,Spring 静态资源
 * 处理器找不到对应文件会返回 404。本控制器将这些路径 forward 到 {@code /index.html},
 * 由 Vue Router 在前端解析。
 *
 * <p>仅转发 SPA 路由,不影响后端端点:{@code /api/**} {@code /v2/**} {@code /ai/**}
 * {@code /redirect/**} 等仍由各自控制器处理。
 */
@Controller
public class SpaForwardController {

    @GetMapping({ "/chat", "/dashboard", "/skills", "/skills/**" })
    public String forward() {
        return "forward:/index.html";
    }
}
