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
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.UrlShortenerRecord;
import com.agentscopea2a.mapper.gauss.UrlShortenerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.Set;

/**
 * 内容下载服务 - 把数据内容直接落 {@code url_shortener} 表, 生成短链.
 *
 * <p>支持两种 content 形态:
 * <ul>
 *   <li>标准 CSV/JSON/文本字符串 - 原样落库</li>
 *   <li>markdown 表 (含 {@code |---|} 分隔行) - mimeType={@code text/csv} 时自动转标准 CSV
 *       (剥离 {@code [sql_registry_exec]} 头尾说明, {@code |} split, 字段 CSV 转义)</li>
 * </ul>
 *
 * <p>场景: {@code SqlRegistryExecTool} 加 {@code downloadFilename} 参数, 跑完 SQL 后调本服务
 * 把 markdown 表转 CSV 落库, 生成短链附工具结果末尾. LLM 不碰 content (不复制不转义).
 *
 * <p>内容由 {@link RedirectController#redirect} 在 {@code record.content} 非空时直接吐字节回吐,
 * 不依赖磁盘 artifact, 跨会话清理 ({@code buildCleanup}) 安全.
 *
 * <p>由 {@code V2ToolConfig.downloadContentService()} 装配为 {@code @Bean},
 * 注入到 {@code SqlRegistryExecTool}.
 */
public class DownloadContentService {

    private static final Logger log = LoggerFactory.getLogger(DownloadContentService.class);

    /** 业务侧内容上限 5MB. */
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    /** MIME 白名单, 防 XSS (拒 text/html 等). */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "text/csv", "application/json", "text/plain", "text/markdown");

    /** 短码字符集 + 长度, 与 {@link UrlShortenerService} 一致. */
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SHORT_CODE_LENGTH = 16;
    private static final int MAX_COLLISION_RETRIES = 5;

    private final UrlShortenerMapper urlShortenerMapper;
    private final Random random = new Random();

    public DownloadContentService(UrlShortenerMapper urlShortenerMapper) {
        this.urlShortenerMapper = urlShortenerMapper;
    }

    /**
     * 把内容落库, 返回 shortCode.
     *
     * @param content   数据内容 (CSV/JSON/markdown 表/纯文本)
     * @param filename  下载文件名, 空则默认 "download.csv"
     * @param mimeType  MIME 类型, 空则默认 "text/csv"; 必须在白名单内
     * @return shortCode (16 位 BASE62)
     * @throws IllegalArgumentException content 空 / 超 5MB / mimeType 不在白名单
     */
    public String create(String content, String filename, String mimeType) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 为空");
        }

        String mime = (mimeType == null || mimeType.isBlank()) ? "text/csv" : mimeType.toLowerCase();
        if (!ALLOWED_MIME.contains(mime)) {
            throw new IllegalArgumentException(
                    "不支持的 mimeType: " + mime + ", 允许: " + ALLOWED_MIME);
        }

        // markdown 表 -> 标准 CSV (仅 text/csv 触发; text/markdown 原样存)
        String finalContent = content;
        if ("text/csv".equals(mime) && MarkdownTableConverter.isMarkdownTable(content)) {
            finalContent = MarkdownTableConverter.toCsv(content);
            log.info("Markdown table -> CSV ({} -> {} chars)", content.length(), finalContent.length());
        }

        byte[] bytes = finalContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "content 超过 5MB 上限: " + bytes.length + " bytes");
        }

        String shortCode = generateUniqueShortCode();
        UrlShortenerRecord record = UrlShortenerRecord.builder()
                .shortCode(shortCode)
                .originalUrl(null)                  // content 模式: 不用 agentPath
                .content(finalContent)
                .filename((filename == null || filename.isBlank()) ? "download.csv" : filename)
                .mimeType(mime)
                .createdAt(LocalDateTime.now())
                .build();
        urlShortenerMapper.insert(record);
        log.info("DownloadContentService: created shortCode={} filename={} ({} bytes)",
                shortCode, record.getFilename(), bytes.length);
        return shortCode;
    }

    private String generateUniqueShortCode() {
        for (int i = 0; i < MAX_COLLISION_RETRIES; i++) {
            String code = generateShortCode();
            if (urlShortenerMapper.selectByShortCode(code) == null) {
                return code;
            }
            log.warn("Short code collision detected, retrying...");
        }
        throw new IllegalStateException(
                "Failed to generate unique short code after " + MAX_COLLISION_RETRIES + " retries");
    }

    private String generateShortCode() {
        StringBuilder sb = new StringBuilder(SHORT_CODE_LENGTH);
        for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
