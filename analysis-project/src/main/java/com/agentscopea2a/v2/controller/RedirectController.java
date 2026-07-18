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

import com.agentscopea2a.v2.service.UrlShortenerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * URL短链重定向控制器 - 根据短码查询原始URL并302重定向。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code GET /redirect/download?shortCode=xxx} - 短链重定向，从 {@code url_shortener} 表
 *       查询原始URL后302跳转；短码不存在或已过期则跳到 {@code /error/404}</li>
 *   <li>{@code GET /download?uuid=xxx} - 模拟下载接口，返回包含 uuid 的示例文本文件</li>
 * </ul>
 *
 * <p>{@code @RestController} 由 Spring 组件扫描自动装配；{@link UrlShortenerService} 通过
 * {@code @Autowired} 注入（service bean 在 {@code V2ToolConfig} 中声明）。
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    @Autowired
    private UrlShortenerService urlShortenerService;

    /**
     * 短链重定向接口 - 根据 shortCode 查询原始URL并302重定向
     */
    @GetMapping("/redirect/download")
    public RedirectView redirect(@RequestParam("shortCode") String shortCode) {
        String originalUrl = urlShortenerService.resolve(shortCode);
        if (originalUrl != null) {
            log.info("Redirecting short_code={} to {}", shortCode, originalUrl);
            return new RedirectView(originalUrl);
        } else {
            log.warn("Short code not found or expired: {}", shortCode);
            return new RedirectView("/error/404");
        }
    }

    /**
     * 模拟下载接口 - 根据 uuid 返回示例文件
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("uuid") String uuid) {
        log.info("Download request: uuid={}", uuid);

        String content = "这是一个模拟下载文件\nUUID: " + uuid + "\n生成时间: " + java.time.LocalDateTime.now();
        byte[] bytes = content.getBytes();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download-" + uuid + ".txt\"")
                .body(bytes);
    }
}
