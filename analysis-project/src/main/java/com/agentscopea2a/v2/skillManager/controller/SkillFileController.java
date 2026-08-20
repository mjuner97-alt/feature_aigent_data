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
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.SkillFileListItem;
import com.agentscopea2a.v2.skillManager.dto.SkillFileUploadResponse;
import com.agentscopea2a.v2.skillManager.service.SkillFileService;
import com.agentscopea2a.v2.util.DownloadErrorPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Skill 文件附件 REST 接口。userId 经 X-User-Id 请求头传入。
 *
 * <p>提供文件上传、下载、列表查询、删除和描述更新功能。
 * 路径挂在 {@code /api/files/...} 下。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillFileController {

    private static final Logger log = LoggerFactory.getLogger(SkillFileController.class);

    private final SkillFileService skillFileService;

    public SkillFileController(SkillFileService skillFileService) {
        this.skillFileService = skillFileService;
    }

    /**
     * 上传文件(multipart/form-data)。
     */
    @PostMapping("/files/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestHeader("X-User-Id") String userId) {
        try {
            SkillFileUploadResponse response = skillFileService.upload(file, description, userId);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列表查询当前用户文件(支持 fileType 筛选)。
     */
    @GetMapping("/files")
    public List<SkillFileListItem> list(
            @RequestParam(value = "fileType", required = false) String fileType,
            @RequestHeader("X-User-Id") String userId) {
        return skillFileService.listUserFiles(userId, fileType);
    }

    /**
     * 下载文件。
     */
    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable(name = "id") Long id,
            @RequestHeader("X-User-Id") String userId) {
        try {
            Resource resource = skillFileService.download(id, userId);
            String filename = skillFileService.getFilename(id, userId);
            // RFC 5987: 中文文件名用 filename*=UTF-8'' 编码,避免明文乱码
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .body(resource);
        } catch (IllegalStateException e) {
            // FileNotFoundOrAccessDenied / FileNotOnDisk - 统一回 "文件不存在" 友好页; 真实原因 (含路径) 只进日志
            log.warn("Skill file download id={} userId={} failed: {}", id, userId, e.getMessage());
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.fileNotFound());
        }
    }

    /**
     * 下载失败时回吐友好 HTML 提示页 (而非空 body 的 404, 浏览器只能显示 "无法访问").
     * 文案由 {@link DownloadErrorPage} 统一生成, 这里按 {@code ResponseEntity<Resource>} 包成
     * {@code ByteArrayResource} 以匹配本类下载方法的返回类型.
     */
    private static ResponseEntity<Resource> htmlResponse(HttpStatus status, String html) {
        Resource body = new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    /**
     * 删除文件(备份后删除)。
     */
    @DeleteMapping("/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable(name = "id") Long id,
            @RequestHeader("X-User-Id") String userId) {
        skillFileService.delete(id, userId);
    }

    /**
     * 更新文件描述。
     */
    @PutMapping("/files/{id}")
    public SkillFileListItem update(
            @PathVariable(name = "id") Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader("X-User-Id") String userId) {
        return skillFileService.updateDescription(id, body.get("description"), userId);
    }
}
