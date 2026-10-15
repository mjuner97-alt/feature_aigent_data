package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;

import java.time.format.DateTimeFormatter;

final class FlowReportNaming {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private FlowReportNaming() {}

    static String datedTitle(String title, SkillFlowExecution execution) {
        return title + dateSuffix(execution);
    }

    static String datedFileName(String fileName, SkillFlowExecution execution) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) return fileName + dateSuffix(execution);
        return fileName.substring(0, extensionIndex) + dateSuffix(execution) + fileName.substring(extensionIndex);
    }

    private static String dateSuffix(SkillFlowExecution execution) {
        return execution.getDataDate() == null ? "" : " (" + execution.getDataDate().format(DATE_FORMAT) + ")";
    }
}
