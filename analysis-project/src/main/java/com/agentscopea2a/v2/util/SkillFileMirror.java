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
package com.agentscopea2a.v2.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Skill Job 报告的备份复制工具.
 *
 * <p>把主目录 {@code {skill.job.base-dir}/{relative}} 的已写入报告复制到
 * {@code {skill.job.backup-dir}/{relative}}（目录结构一致，只换根）。
 * 主文件不存在时，下载端点从备份目录回退。
 *
 * <p><b>最佳努力</b>: 镜像失败只记日志, 绝不抛出/阻断上传、报告生成、下载等主流程
 * （与 {@code SkillFileService.backupFile} 同风格）。
 *
 * <p><b>防自拷贝</b>: mirror-dir 为空、或等于/位于 base-dir 内时直接跳过, 避免把副本写回主目录自身.
 */
public final class SkillFileMirror {

    private static final Logger log = LoggerFactory.getLogger(SkillFileMirror.class);

    private SkillFileMirror() {}

    /**
     * 把 {@code {baseDir}/{relative}} 拷贝到 {@code {mirrorDir}/{relative}}。
     *
     * @param baseDir      报告主目录根 ({@code skill.job.base-dir})
     * @param mirrorDir    报告备份目录根 ({@code skill.job.backup-dir}); 为空/非法时跳过
     * @param relativePath 相对路径, 形如 {@code {userId}/{filename}} 或 {@code {userId}/reports/xxx.html}
     */
    public static void mirror(String baseDir, String mirrorDir, String relativePath) {
        if (mirrorDir == null || mirrorDir.isBlank()) {
            return;
        }
        if (baseDir == null || relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path base = Paths.get(baseDir).normalize();
        Path mirrorRoot = Paths.get(mirrorDir).normalize();
        if (mirrorRoot.equals(base) || mirrorRoot.startsWith(base)) {
            log.warn("SkillFileMirror skipped: mirror-dir inside base-dir (mirror={}, base={})", mirrorRoot, base);
            return;
        }
        try {
            Path source = Paths.get(baseDir, relativePath).normalize();
            if (!Files.exists(source)) {
                return;
            }
            Path target = Paths.get(mirrorDir, relativePath).normalize();
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("SkillFileMirror: {} -> {}", source, target);
        } catch (IOException e) {
            log.warn("SkillFileMirror failed for {} (best effort, ignored): {}",
                    relativePath, e.getMessage());
        }
    }
}
