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
package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.SkillFileListItem;
import com.agentscopea2a.v2.skillManager.dto.SkillFileUploadResponse;
import com.agentscopea2a.v2.skillManager.entity.SkillFile;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Skill 文件附件 Service。处理文件上传/下载/删除/备份，
 * 支持同名文件覆盖时自动备份旧版本。
 *
 * <p>文件存储路径: {@code {baseDir}/{userId}/{filename}}
 * <p>备份路径: {@code {baseDir}/{userId}/{backupDir}/{filename}_{timestamp}}
 *
 * <p>DB 操作使用 {@code gaussTransactionManager} 事务管理器；
 * 磁盘操作在事务外执行，避免长事务持有文件锁。
 */
@Service
public class SkillFileService {

    private static final Logger log = LoggerFactory.getLogger(SkillFileService.class);
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SkillMapper skillMapper;

    @Value("${skill.file.base-dir:/data/skill-files}")
    private String baseDir;

    @Value("${skill.file.max-size-bytes:1048576}")
    private long maxSizeBytes;

    @Value("${skill.file.allowed-extensions:.py,.sql}")
    private String allowedExtensions;

    @Value("${skill.file.backup-dir:.backup}")
    private String backupDir;

    public SkillFileService(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    // ==================== 上传 ====================

    /**
     * 上传文件。校验大小与扩展名，同名文件自动备份旧版本后覆盖。
     */
    @Transactional("gaussTransactionManager")
    public SkillFileUploadResponse upload(MultipartFile file, String description, String userId) {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();

        // 校验文件大小
        if (fileSize > maxSizeBytes) {
            throw new IllegalStateException("FileSizeExceeded: max=" + maxSizeBytes + ", actual=" + fileSize);
        }

        // 校验文件扩展名
        String extension = getExtension(filename);
        if (!isAllowedExtension(extension)) {
            throw new IllegalStateException("FileExtensionNotAllowed: " + extension + ", allowed=" + allowedExtensions);
        }

        String fileType = extensionToFileType(extension);
        Path storagePath = Paths.get(baseDir, userId, filename);

        // 查询是否已存在同名文件
        SkillFile existing = skillMapper.selectFileByUserIdAndFilename(userId, filename);

        if (existing != null) {
            // 备份旧文件
            Path oldFilePath = Paths.get(existing.getStoragePath());
            if (Files.exists(oldFilePath)) {
                backupFile(oldFilePath, Paths.get(baseDir, userId), backupDir, filename);
            }

            // 覆盖磁盘文件
            try {
                Files.createDirectories(storagePath.getParent());
                Files.copy(file.getInputStream(), storagePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("FileWriteFailed: " + storagePath, e);
            }

            // 更新 DB 记录
            existing.setStoragePath(storagePath.toString());
            existing.setFileSize(fileSize);
            existing.setFileType(fileType);
            if (description != null) existing.setDescription(description);
            existing.setUpdatedAt(LocalDateTime.now());
            skillMapper.updateSkillFile(existing);

            return new SkillFileUploadResponse(
                    existing.getId(), filename, fileType, fileSize,
                    existing.getDescription(), existing.getCreatedAt().toString());
        }

        // 新文件: 创建目录 + 写入磁盘 + 插入 DB
        try {
            Files.createDirectories(storagePath.getParent());
            Files.copy(file.getInputStream(), storagePath);
        } catch (IOException e) {
            throw new IllegalStateException("FileWriteFailed: " + storagePath, e);
        }

        SkillFile skillFile = new SkillFile();
        skillFile.setUserId(userId);
        skillFile.setFilename(filename);
        skillFile.setStoragePath(storagePath.toString());
        skillFile.setFileSize(fileSize);
        skillFile.setFileType(fileType);
        skillFile.setDescription(description);
        skillFile.setCreatedAt(LocalDateTime.now());
        skillFile.setUpdatedAt(LocalDateTime.now());
        skillMapper.insertSkillFile(skillFile);

        return new SkillFileUploadResponse(
                skillFile.getId(), filename, fileType, fileSize,
                description, skillFile.getCreatedAt().toString());
    }

    // ==================== 列表查询 ====================

    /**
     * 查询用户上传的文件列表，可按 fileType 过滤。
     */
    public List<SkillFileListItem> listUserFiles(String userId, String fileType) {
        List<SkillFile> files = skillMapper.selectFilesByUserId(userId, fileType);
        return files.stream().map(f -> new SkillFileListItem(
                f.getId(), f.getFilename(), f.getFileType(), f.getFileSize(),
                f.getDescription(),
                f.getCreatedAt() != null ? f.getCreatedAt().toString() : null,
                f.getUpdatedAt() != null ? f.getUpdatedAt().toString() : null
        )).toList();
    }

    // ==================== 下载 ====================

    /**
     * 下载文件。校验 userId 归属后返回磁盘文件 Resource。
     */
    public Resource download(Long fileId, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }

        Path path = Paths.get(skillFile.getStoragePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("FileNotOnDisk: " + skillFile.getStoragePath());
        }

        return new FileSystemResource(path);
    }

    /**
     * 获取文件名(供下载时设置 Content-Disposition)。
     */
    public String getFilename(Long fileId, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }
        return skillFile.getFilename();
    }

    // ==================== 删除 ====================

    /**
     * 删除文件。先备份再删磁盘文件，事务内级联删除 DB 引用记录与文件记录。
     */
    @Transactional("gaussTransactionManager")
    public void delete(Long fileId, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }

        // 磁盘操作: 备份 + 删除
        Path filePath = Paths.get(skillFile.getStoragePath());
        if (Files.exists(filePath)) {
            backupFile(filePath, Paths.get(baseDir, userId), backupDir, skillFile.getFilename());
            try {
                Files.delete(filePath);
            } catch (IOException e) {
                log.error("FileDeleteFailed: {}", filePath, e);
                throw new IllegalStateException("FileDeleteFailed: " + filePath, e);
            }
        }

        // DB 操作: 级联删除引用记录 + 文件记录
        skillMapper.deleteFileReferencesByFileId(fileId);
        skillMapper.deleteSkillFile(fileId);
    }

    // ==================== 更新描述 ====================

    /**
     * 更新文件描述。
     */
    @Transactional("gaussTransactionManager")
    public SkillFileListItem updateDescription(Long fileId, String description, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }

        skillFile.setDescription(description);
        skillFile.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateSkillFile(skillFile);

        return new SkillFileListItem(
                skillFile.getId(), skillFile.getFilename(), skillFile.getFileType(),
                skillFile.getFileSize(), skillFile.getDescription(),
                skillFile.getCreatedAt() != null ? skillFile.getCreatedAt().toString() : null,
                skillFile.getUpdatedAt() != null ? skillFile.getUpdatedAt().toString() : null
        );
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取文件存储路径(供下载等场景使用)。
     */
    public String getFilePath(Long fileId, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }
        return skillFile.getStoragePath();
    }

    // ==================== 私有工具方法 ====================

    private void backupFile(Path source, Path baseDir, String backupDir, String filename) {
        try {
            Path backupDirPath = baseDir.resolve(backupDir);
            Files.createDirectories(backupDirPath);
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path backupPath = backupDirPath.resolve(filename + "_" + timestamp);
            Files.copy(source, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("file backed up: {} -> {}", source, backupPath);
        } catch (IOException e) {
            log.error("FileBackupFailed: source={}", source, e);
            throw new IllegalStateException("FileBackupFailed: " + source, e);
        }
    }

    private String extensionToFileType(String extension) {
        return switch (extension.toLowerCase()) {
            case ".py" -> "PYTHON";
            case ".sql" -> "SQL";
            default -> throw new IllegalStateException("UnsupportedExtension: " + extension);
        };
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return false;
        }
        String[] allowed = allowedExtensions.split(",");
        for (String ext : allowed) {
            if (extension.equalsIgnoreCase(ext.trim())) {
                return true;
            }
        }
        return false;
    }
}
