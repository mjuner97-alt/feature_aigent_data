package com.agentscopea2a.v2.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Skill 文件存储配置，所有目录统一从 application.properties 读取。 */
@Getter
@Component
public class SkillStorageProperties {

    @Value("${skill.file.script}")
    private String scriptDir;

    @Value("${skill.file.max-size-bytes:1048576}")
    private long maxSizeBytes;

    @Value("${skill.file.allowed-extensions:.py,.sql}")
    private String allowedExtensions;

    @Value("${skill.job.base-dir}")
    private String jobReportDir;

    @Value("${skill.job.backup-dir}")
    private String jobBackupDir;

    public String getJobReportDir() {
        return ReportStoragePathResolver.resolve(jobReportDir).toString();
    }

    public String getJobBackupDir() {
        return ReportStoragePathResolver.resolve(jobBackupDir).toString();
    }
}
