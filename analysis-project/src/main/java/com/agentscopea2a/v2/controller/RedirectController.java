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

import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.service.UrlShortenerService;
import com.agentscopea2a.v2.util.DownloadErrorPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * CSV 短链下载控制器 - 解 shortCode 后从 {@link ArtifactStore} 流式吐 CSV 文件.
 *
 * <p>链路: {@link com.agentscopea2a.v2.tools.CsvDownloadTool#generateCsvDownloadUrl}
 * 把 agentPath 编码进 {@code /download?path=...} URL, 用 {@link UrlShortenerService#shorten}
 * 生成 16 位 BASE62 shortCode. 用户点 {@code /redirect/download?shortCode=xxx} 时,
 * 本控制器解 shortCode 拿原始 URL, 提取 path 参数, 校验后从磁盘读 CSV 回吐.
 *
 * <p><b>安全</b>:
 * <ul>
 *   <li>shortCode 是 95-bit 密钥, 不可枚举 (16 位 BASE62)</li>
 *   <li>agentPath 必须以 {@code /workspace/artifacts/} 开头, 拒绝 {@code ..} 穿越</li>
 *   <li>拼出的磁盘路径 normalize 后必须仍在 {@link ArtifactStore#artifactsRoot()} 下 (双保险)</li>
 * </ul>
 *
 * <p>旧 {@code /download?uuid=xxx} 模拟端点已删除 (没人调).
 * 旧 {@code DownloadTool.generateDownloadUrl()} 测试桩也已删除.
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    /** 必须与 {@link com.agentscopea2a.v2.tools.CsvDownloadTool#MOUNT_PREFIX} 对齐. */
    private static final String MOUNT_PREFIX = "/workspace/artifacts";

    @Autowired
    private UrlShortenerService urlShortenerService;

    @Autowired
    private ArtifactStore artifactStore;

    /**
     * 短链下载 - 解 shortCode -> 解 path -> 校验 -> 通过 ArtifactStore.io 读 CSV 字节回吐.
     *
     * <p>不再 302 重定向到 {@code /download?uuid=xxx}, 直接在本端点吐文件流.
     *
     * <p><b>必须走 {@link ArtifactStore#read(String)} 而不是 Files.readAllBytes</b>: dev profile
     * CSV 走 SshArtifactIo 落在远端 docker-host, 本地 FS 看不到. io delegate 决定字节实际从哪读.
     */
    @GetMapping("/redirect/download")
    public ResponseEntity<byte[]> redirect(@RequestParam("shortCode") String shortCode) throws IOException {
        String downloadUrl = urlShortenerService.resolve(shortCode);
        if (downloadUrl == null) {
            log.warn("Short code not found or expired: {}", shortCode);
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.linkInvalidOrExpired());
        }

        String agentPath = extractAgentPath(downloadUrl);
        if (agentPath == null) {
            log.warn("Short code {} resolved to URL without path param: {}", shortCode, downloadUrl);
            return htmlResponse(HttpStatus.BAD_REQUEST, DownloadErrorPage.linkInvalid());
        }

        // 服务端二次校验 (防 shortCode 表被注入或绕过 CsvDownloadTool 直接调 shorten)
        if (agentPath.contains("..") || !agentPath.startsWith(MOUNT_PREFIX)) {
            log.warn("Blocked agentPath outside mount prefix or contains ..: {}", agentPath);
            return htmlResponse(HttpStatus.BAD_REQUEST, DownloadErrorPage.linkInvalid());
        }

        byte[] bytes = artifactStore.read(agentPath);
        if (bytes == null || bytes.length == 0) {
            log.warn("CSV not found for shortCode={}: agentPath={}", shortCode, agentPath);
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.fileNotFound());
        }

        String filename = extractFilename(agentPath);
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);

        log.info("CSV download: shortCode={} -> {} ({} bytes)", shortCode, agentPath, bytes.length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    /** 从 agentPath 末段取 filename, 形如 {@code /workspace/artifacts/.../<file>.csv}. */
    private static String extractFilename(String agentPath) {
        int slash = agentPath.lastIndexOf('/');
        return slash >= 0 ? agentPath.substring(slash + 1) : agentPath;
    }

    /**
     * 短链下载失败时回吐一个友好的 HTML 提示页, 而不是空 body.
     *
     * <p>空 body 的 404/400 浏览器只能显示 "无法访问" 之类的通用错误, 用户不知道是链接失效还是文件没了.
     * HTML 文案由 {@link DownloadErrorPage} 统一生成, 这里只负责按 {@code ResponseEntity<byte[]>} 包装.
     */
    private static ResponseEntity<byte[]> htmlResponse(HttpStatus status, String html) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从 {@code /download?path=xxx} 形式的 URL 中提取 path 参数 (URL-decoded).
     *
     * @return agentPath, 或 null 如果 URL 不含 path 参数
     */
    private static String extractAgentPath(String downloadUrl) {
        URI uri = URI.create(downloadUrl);
        String query = uri.getRawQuery();
        if (query == null || !query.startsWith("path=")) {
            return null;
        }
        String encoded = query.substring("path=".length());
        // 容忍 path 后还有其他参数 (虽然 CsvDownloadTool 不会加)
        int amp = encoded.indexOf('&');
        if (amp >= 0) {
            encoded = encoded.substring(0, amp);
        }
        return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
