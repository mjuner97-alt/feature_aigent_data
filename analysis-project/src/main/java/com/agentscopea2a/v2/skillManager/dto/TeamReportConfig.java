package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 部门及团队报告配置，包含模式、部门和独立执行对象。
 * 历史流程未配置此对象时继续使用原执行方式；旧单部门配置会补齐部门列表。
 */
public record TeamReportConfig(
    String reportType,
    String departmentId,
    String departmentName,
    List<Target> targets,
    List<Department> departments
) {
    /** 部门稳定标识与名称，保存时由来源数据校验。 */
    public record Department(String id, String name) {}

    public TeamReportConfig(String reportType, String departmentId, String departmentName, List<Target> targets) {
        this(reportType, departmentId, departmentName, targets, null);
    }

    public TeamReportConfig {
        // 兼容旧单部门配置，并为缺少部门信息的历史对象补齐归属。
        departments =
            departments == null || departments.isEmpty()
                ? departmentId == null || departmentId.isBlank()
                    ? List.of()
                    : List.of(new Department(departmentId, departmentName))
                : List.copyOf(departments);
        String fallbackId = departmentId,
            fallbackName = departmentName;
        targets =
            targets == null
                ? List.of()
                : targets
                      .stream()
                      .map(t -> t.departmentId() == null ? t.inDepartment(fallbackId, fallbackName) : t)
                      .toList();
    }

    /** 单份报告的参数与通知配置；部门报告的团队标识为空。 */
    public record Target(
        String teamId,
        String teamName,
        boolean selected,
        String keyword,
        List<String> to,
        List<String> cc,
        String notifyContactName,
        String notifyContactPhone,
        List<String> executors,
        Boolean notifyEnabled,
        String notifyBodyTemplate,
        String reportName,
        String departmentId,
        String departmentName
    ) {
        public Target(
            String teamId,
            String teamName,
            boolean selected,
            String keyword,
            List<String> to,
            List<String> cc,
            String notifyContactName,
            String notifyContactPhone,
            List<String> executors,
            Boolean notifyEnabled,
            String notifyBodyTemplate,
            String reportName
        ) {
            this(
                teamId,
                teamName,
                selected,
                keyword,
                to,
                cc,
                notifyContactName,
                notifyContactPhone,
                executors,
                notifyEnabled,
                notifyBodyTemplate,
                reportName,
                null,
                null
            );
        }

        public Target inDepartment(String id, String name) {
            return new Target(
                teamId,
                teamName,
                selected,
                keyword,
                to,
                cc,
                notifyContactName,
                notifyContactPhone,
                executors,
                notifyEnabled,
                notifyBodyTemplate,
                reportName,
                id,
                name
            );
        }

        public Target(
            String teamId,
            String teamName,
            boolean selected,
            String keyword,
            List<String> to,
            List<String> cc,
            String notifyContactName,
            String notifyContactPhone,
            List<String> executors,
            Boolean notifyEnabled,
            String notifyBodyTemplate
        ) {
            this(
                teamId,
                teamName,
                selected,
                keyword,
                to,
                cc,
                notifyContactName,
                notifyContactPhone,
                executors,
                notifyEnabled,
                notifyBodyTemplate,
                null
            );
        }

        public Target(
            String teamId,
            String teamName,
            boolean selected,
            String keyword,
            List<String> to,
            List<String> cc,
            String notifyContactName,
            String notifyContactPhone
        ) {
            this(teamId, teamName, selected, keyword, to, cc, notifyContactName, notifyContactPhone, null, null, null);
        }

        public Target(
            String teamId,
            String teamName,
            boolean selected,
            String keyword,
            List<String> to,
            List<String> cc
        ) {
            this(teamId, teamName, selected, keyword, to, cc, null, null);
        }

        public Target {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            executors = executors == null ? null : List.copyOf(executors);
        }
    }
}
