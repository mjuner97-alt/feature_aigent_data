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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Skill 文件附件 Service。处理文件上传/下载/删除/备份，
 * 支持同名文件覆盖时自动备份旧版本。
 *
 * <p>存储: DB 仅存相对路径 {@code {userId}/{filename}},运行时拼 {@code baseDir} 解析,
 * 避免换机器/换配置后路径失效。每个用户独立子目录,同名文件按 userId 隔离。
 * <p>备份: {@code {baseDir}/{userId}/{backupDir}/{filename}_{timestamp}},按文件保留最近 N 份。
 *
 * <p>一致性策略:
 * <ul>
 *   <li>上传(新文件): 先写盘后 insert;insert 失败(含并发同名竞态)清理磁盘,避免孤儿。</li>
 *   <li>上传(覆盖): 备份旧文件 -> 写新文件 -> 更新 DB;DB 失败则用备份回滚磁盘。</li>
 *   <li>删除: 先删 DB(事务内)再删磁盘;磁盘失败仅记日志不回滚(DB 已删,至多遗留孤儿)。</li>
 * </ul>
 * DB 操作使用 {@code gaussTransactionManager} 事务管理器。
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

    /** 每个文件保留的备份份数,超出按时间倒序删除旧备份;<=0 表示不清理。 */
    @Value("${skill.file.backup-max-keep:5}")
    private int backupMaxKeep;

    public SkillFileService(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    // ==================== 上传 ====================

    /**
     * 上传文件。校验文件名/大小/扩展名/内容,同名文件自动备份旧版本后覆盖。
     * DB 仅存相对路径 {@code {userId}/{filename}}。
     */
    @Transactional("gaussTransactionManager")
    public SkillFileUploadResponse upload(MultipartFile file, String description, String userId) {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();

        // 校验文件名(防路径穿越: 禁止路径分隔符 / .. / 控制字符)
        validateFilename(filename);

        // 校验文件大小
        if (fileSize > maxSizeBytes) {
            throw new IllegalStateException("FileSizeExceeded: max=" + maxSizeBytes + ", actual=" + fileSize);
        }

        // 校验文件扩展名
        String extension = getExtension(filename);
        if (!isAllowedExtension(extension)) {
            throw new IllegalStateException("FileExtensionNotAllowed: " + extension + ", allowed=" + allowedExtensions);
        }

        // 读取内容(一次性),供内容校验与写盘共用,避免重复读流
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("FileReadFailed: " + filename, e);
        }
        // 内容校验: .py/.sql 应为文本,出现 NUL 字节视为二进制(防伪装可执行)
        if (isBinary(content)) {
            throw new IllegalStateException("FileBinaryNotAllowed: " + filename);
        }

        String fileType = extensionToFileType(extension);
        String relativePath = Paths.get(userId, filename).toString(); // 相对路径
        Path storagePath = resolveStoragePath(relativePath);

        SkillFile existing = skillMapper.selectFileByUserIdAndFilename(userId, filename);

        if (existing != null) {
            return overwriteExisting(existing, content, fileSize, fileType, description, filename);
        }

        // 新文件: 先写盘,再 insert;insert 失败(含并发同名 DuplicateKey)清理磁盘
        try {
            Files.createDirectories(storagePath.getParent());
            Files.copy(new ByteArrayInputStream(content), storagePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("FileWriteFailed: " + storagePath, e);
        }

        SkillFile skillFile = new SkillFile();
        skillFile.setUserId(userId);
        skillFile.setFilename(filename);
        skillFile.setStoragePath(relativePath);
        skillFile.setFileSize(fileSize);
        skillFile.setFileType(fileType);
        skillFile.setDescription(description);
        skillFile.setCreatedAt(LocalDateTime.now());
        skillFile.setUpdatedAt(LocalDateTime.now());
        try {
            skillMapper.insertSkillFile(skillFile);
        } catch (RuntimeException e) {
            // insert 失败: 清理本次写入的磁盘文件,避免孤儿
            safeDelete(storagePath);
            if (e instanceof DuplicateKeyException) {
                // 并发上传同名: 另一请求已建记录
                throw new IllegalStateException("FileConcurrentUpload: " + filename, e);
            }
            throw e;
        }

        return new SkillFileUploadResponse(
                skillFile.getId(), filename, fileType, fileSize,
                description, skillFile.getCreatedAt().toString());
    }

    /**
     * 覆盖已存在文件: 备份旧 -> 写新 -> 更新 DB;DB 失败用备份回滚磁盘。
     */
    private SkillFileUploadResponse overwriteExisting(SkillFile existing, byte[] content, long fileSize,
                                                      String fileType, String description, String filename) {
        String userId = existing.getUserId();
        Path oldFilePath = resolveStoragePath(existing.getStoragePath());
        Path storagePath = resolveStoragePath(Paths.get(userId, filename).toString());

        // 备份旧文件(最佳努力,失败不阻断)
        Path backupPath = Files.exists(oldFilePath)
                ? backupFile(oldFilePath, Paths.get(baseDir, userId), backupDir, filename)
                : null;

        // 覆盖磁盘文件
        try {
            Files.createDirectories(storagePath.getParent());
            Files.copy(new ByteArrayInputStream(content), storagePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("FileWriteFailed: " + storagePath, e);
        }

        // 更新 DB 记录;失败则回滚磁盘到旧版本
        existing.setStoragePath(Paths.get(userId, filename).toString());
        existing.setFileSize(fileSize);
        existing.setFileType(fileType);
        if (description != null) existing.setDescription(description);
        existing.setUpdatedAt(LocalDateTime.now());
        try {
            skillMapper.updateSkillFile(existing);
        } catch (RuntimeException e) {
            if (backupPath != null) {
                try {
                    Files.copy(backupPath, oldFilePath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("file restored after DB update failure: {} -> {}", backupPath, oldFilePath);
                } catch (IOException restoreEx) {
                    log.error("FileRestoreFailed: {} -> {}", backupPath, oldFilePath, restoreEx);
                }
            }
            throw e;
        }

        return new SkillFileUploadResponse(
                existing.getId(), filename, fileType, fileSize,
                existing.getDescription(), existing.getCreatedAt().toString());
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

        Path path = resolveStoragePath(skillFile.getStoragePath());
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
     * 删除文件。先删 DB(事务内: 级联引用 + 文件记录),再清理磁盘(失败仅记日志不回滚)。
     * 这样 DB 失败时磁盘未动(一致);磁盘失败时 DB 已删(至多遗留孤儿文件,无害)。
     */
    @Transactional("gaussTransactionManager")
    public void delete(Long fileId, String userId) {
        SkillFile skillFile = skillMapper.selectFileById(fileId);
        if (skillFile == null || !skillFile.getUserId().equals(userId)) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + fileId);
        }

        // DB: 级联删除引用记录 + 文件记录(事务内)
        skillMapper.deleteFileReferencesByFileId(fileId);
        skillMapper.deleteSkillFile(fileId);

        // 磁盘: 备份 + 删除(最佳努力,失败不回滚 DB)
        Path filePath = resolveStoragePath(skillFile.getStoragePath());
        if (Files.exists(filePath)) {
            backupFile(filePath, Paths.get(baseDir, userId), backupDir, skillFile.getFilename());
            try {
                Files.delete(filePath);
            } catch (IOException e) {
                log.warn("FileDeleteFailed (orphaned on disk, DB record removed): {}", filePath, e);
            }
        }
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

    /**
     * 解析存储路径为绝对路径。DB 存相对路径 {@code {userId}/{filename}},
     * 历史绝对路径记录也可正常解析(Paths.get 在第二参数为绝对路径时忽略 baseDir)。
     */
    private Path resolveStoragePath(String storagePath) {
        return Paths.get(baseDir, storagePath);
    }

    /**
     * 文件名校验: 防路径穿越(禁止 / \ 及 . ..)、控制字符、空名、超长。
     */
    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalStateException("FileNameEmpty");
        }
        if (filename.length() > 255) {
            throw new IllegalStateException("FileNameTooLong: " + filename.length());
        }
        if (filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals("..")) {
            throw new IllegalStateException("FileNameInvalid: " + filename);
        }
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalStateException("FileNameInvalidControlChar: " + filename);
            }
        }
    }

    /**
     * 简易二进制检测: 出现 NUL 字节视为二进制。.py/.sql 应为纯文本。
     */
    private boolean isBinary(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** 删除文件(忽略不存在与失败,仅记日志)。 */
    private void safeDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("safeDelete failed: {}", path, e);
        }
    }

    /**
     * 备份文件(最佳努力)。返回备份路径,失败返回 null(仅记日志,不抛)。
     * 同时按 filename 前缀清理超出保留数量的旧备份。
     */
    private Path backupFile(Path source, Path userBaseDir, String backupDirName, String filename) {
        try {
            Path backupDirPath = userBaseDir.resolve(backupDirName);
            Files.createDirectories(backupDirPath);
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path backupPath = backupDirPath.resolve(filename + "_" + timestamp);
            Files.copy(source, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("file backed up: {} -> {}", source, backupPath);
            pruneBackups(backupDirPath, filename, backupMaxKeep);
            return backupPath;
        } catch (IOException e) {
            log.error("FileBackupFailed: source={}", source, e);
            return null;
        }
    }

    /**
     * 保留每个文件最近 maxKeep 份备份,其余按时间倒序删除。
     * 备份命名约定 {@code {filename}_{timestamp}},字典序倒序即时间倒序。
     */
    private void pruneBackups(Path backupDirPath, String filename, int maxKeep) {
        if (maxKeep <= 0) {
            return;
        }
        String prefix = filename + "_";
        try (Stream<Path> stream = Files.list(backupDirPath)) {
            List<Path> matches = stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
            for (int i = maxKeep; i < matches.size(); i++) {
                try {
                    Files.delete(matches.get(i));
                } catch (IOException ignore) {
                    // 单个旧备份删除失败不影响主流程
                }
            }
        } catch (IOException e) {
            log.debug("pruneBackups list failed: {}", backupDirPath, e);
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
