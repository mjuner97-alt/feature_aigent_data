package com.agentscopea2a.v2.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Skill 文件存储配置；独立任务与长任务共用 jobReportDir 报告根目录。 */
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

    @Value("${skill.job.report-min-free-bytes:104857600}")
    private long reportMinFreeBytes;

    @Value("${skill.job.report-retention-days:30}")
    private int reportRetentionDays;

    public String getJobReportDir() {
        return ReportStoragePathResolver.resolve(jobReportDir).toString();
    }

    public String getJobBackupDir() {
        return ReportStoragePathResolver.resolve(jobBackupDir).toString();
    }
}
