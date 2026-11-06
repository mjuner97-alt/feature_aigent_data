package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import java.time.format.DateTimeFormatter;

/** 根据执行快照生成报告标题、通知标题和文件名，避免后续编辑影响历史报告。 */
public final class FlowReportNaming {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private FlowReportNaming() {}

    /** 优先使用执行快照中的报告名称，历史执行按原有规则追加日期。 */
    public static String datedTitle(String title, SkillFlowExecution execution) {
        if (execution.getBatchId() != null) {
            String saved = value(execution, "report_name");
            if (saved != null && !saved.isBlank()) return saved;
        }
        return (execution.getBatchId() == null ? title : baseTitle(execution)) + dateSuffix(execution);
    }

    /** 通知标题附加执行日期，不修改已保存的报告名称。 */
    public static String notificationTitle(SkillFlowExecution execution) {
        if (execution.getBatchId() == null || !"团队报告".equals(value(execution, "report_type"))) return datedTitle(
            execution.getFlowName(),
            execution
        );
        String name = baseTitle(execution);
        if (name == null || name.isBlank()) name = "团队报告";
        if (execution.getDataDate() == null || name.matches(".*[（(]\\d{4}-\\d{2}-\\d{2}[）)]$")) return name;
        return name + "（" + execution.getDataDate() + "）";
    }

    public static String value(SkillFlowExecution execution, String key) {
        if (execution.getPublicParamsJson() == null) return null;
        try {
            return JSON.readTree(execution.getPublicParamsJson()).path(key).asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("ReportExecutionContextInvalid", e);
        }
    }

    /** 从对象执行快照解析报告基础名称，缺失时回退到部门和团队名称。 */
    public static String baseTitle(SkillFlowExecution execution) {
        if (execution.getBatchId() == null) return execution.getFlowName();
        String saved = value(execution, "report_name");
        if (saved != null && !saved.isBlank()) return saved;
        String department = value(execution, "dev_dept"),
            team = value(execution, "stat_group");
        return department + (team == null ? "" : team);
    }

    /** 生成可保存的报告文件名，清理非法字符并保留原扩展名。 */
    static String datedFileName(String fileName, SkillFlowExecution execution) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (execution.getBatchId() != null) {
            String name = datedTitle(execution.getFlowName(), execution)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "")
                .trim();
            if (name.isEmpty()) name = "flow-report";
            if (name.length() > 150) name = name.substring(0, 150);
            return name + (extensionIndex > 0 ? fileName.substring(extensionIndex) : "");
        }
        if (extensionIndex <= 0) return fileName + dateSuffix(execution);
        return fileName.substring(0, extensionIndex) + dateSuffix(execution) + fileName.substring(extensionIndex);
    }

    private static String dateSuffix(SkillFlowExecution execution) {
        if (execution.getBatchId() != null) return execution.getDataDate() == null
            ? ""
            : "（" + execution.getDataDate() + "）";
        return execution.getDataDate() == null ? "" : " (" + execution.getDataDate().format(DATE_FORMAT) + ")";
    }
}
