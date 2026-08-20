package com.agentscopea2a.v2.skillManager.report;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Skill Job 报告文件名生成策略:任务名(清洗非法字符)+ 数据日期 + 执行 ID,保证文件名可移植、可读。
 */
public final class ReportFilenamePolicy {

    private ReportFilenamePolicy() {}

    public static String build(String jobName, LocalDate date, long executionId) {
        String safeName = jobName == null ? "" : jobName
                .replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]+", "_")
                .replaceAll("[\\s_]+", "_")
                .replaceAll("^[\\s._]+|[\\s._]+$", "");
        if (safeName.isBlank()) {
            safeName = "skill-job";
        }
        return safeName + "_" + Objects.requireNonNull(date, "date") + "_" + executionId + ".html";
    }
}
