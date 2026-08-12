package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.skillManager.scheduler.WriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

/**
 * 将内容写入指定MD文件路径，由SkillJobScheduler在Agent执行完成后直接Java调用。
 *
 * <p>不通过tool_call机制暴露给AI，executionId由调用方直接传入，
 * 写入成功后通过{@link WriteCallback}通知Scheduler标记md_file_written。
 *
 * <p>写入根目录为 {@code {skill.file.base-dir}/{userId}/}（可配置，与 SkillFileService 一致），
 * filePath 参数作为相对子路径，规范化后必须仍在该根目录内，防止路径穿越。
 *
 * <p>使用 {@link Supplier} 延迟获取 {@link WriteCallback}，避免与 SkillJobScheduler 的循环依赖：
 * SkillJobScheduler 构造时注入本 Tool（此时 callback 尚未就绪），
 * 等到运行时 writeMarkdown 被调用时才通过 supplier 解析 callback。
 */
public class WriteMarkdownTool {

    private static final Logger log = LoggerFactory.getLogger(WriteMarkdownTool.class);

    /** skill 文件磁盘根目录(${skill.file.base-dir})，与 SkillFileService 一致。 */
    private final String baseDir;

    /** 延迟获取的回调，SkillJobScheduler实现，用于标记md_file_written=true */
    private final Supplier<WriteCallback> writeCallbackSupplier;

    public WriteMarkdownTool(Supplier<WriteCallback> writeCallbackSupplier, String baseDir) {
        this.writeCallbackSupplier = writeCallbackSupplier;
        this.baseDir = baseDir;
    }

    /**
     * 写入MD文件。流程：参数校验->路径穿越防护->自动创建父目录->写入文件->验证写入->通知WriteCallback回调。
     *
     * <p>最终写入路径为 {@code {skill.file.base-dir}/{userId}/{filePath}}，
     * filePath 相对于该 userId 根目录，禁止 {@code ..} 穿越。
     * 回调传回相对路径 {@code {userId}/{filePath}}，供 execution.resolved_output_path 存储；
     * 下载时由 baseDir + createdBy 拼绝对路径，baseDir 可配置不写死。
     *
     * @param filePath    相对 userId 目录的MD文件子路径（如 reports/daily/2026-08-05.md）
     * @param content     Markdown内容
     * @param executionId 当前SkillJob执行的executionId，用于回调时定位
     * @param userId      用户ID，用于隔离不同用户的写入目录
     * @return true=写入成功，false=写入失败
     */
    public boolean writeMarkdown(String filePath, String content, Long executionId, String userId) {
        try {
            if (filePath == null || filePath.isBlank()) {
                log.warn("WriteMarkdownTool: file_path is blank");
                return false;
            }
            if (content == null || content.isBlank()) {
                log.warn("WriteMarkdownTool: content is blank");
                return false;
            }
            if (userId == null || userId.isBlank()) {
                log.warn("WriteMarkdownTool: userId is blank");
                return false;
            }

            // 解析根目录: {baseDir}/{userId}/ (baseDir 来自 skill.file.base-dir 配置)
            Path userBaseDir = Paths.get(baseDir, userId).normalize().toAbsolutePath();
            // 解析目标文件: userBaseDir + filePath
            Path resolved = userBaseDir.resolve(filePath).normalize().toAbsolutePath();

            // 路径穿越防护：目标文件必须在 userBaseDir 内
            if (!resolved.startsWith(userBaseDir)) {
                log.warn("WriteMarkdownTool: path traversal detected: {} (base={})", filePath, userBaseDir);
                return false;
            }

            // 自动创建父目录
            Path parentDir = resolved.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // 写入文件
            Files.writeString(resolved, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 验证写入
            if (!Files.exists(resolved) || Files.size(resolved) == 0) {
                log.warn("WriteMarkdownTool: write verification failed: {}", resolved);
                return false;
            }

            // 通知回调：传回相对路径 {userId}/{filePath}，存入 execution.resolved_output_path；
            // 下载时由 baseDir + createdBy 拼绝对路径，baseDir 可配置不写死。
            WriteCallback writeCallback = writeCallbackSupplier.get();
            if (writeCallback != null) {
                writeCallback.onMarkdownWritten(userId + "/" + filePath, executionId);
            }

            log.info("WriteMarkdownTool: written {} bytes to {}", content.length(), resolved);
            return true;
        } catch (Exception e) {
            log.error("WriteMarkdownTool: failed to write {}", filePath, e);
            return false;
        }
    }
}
