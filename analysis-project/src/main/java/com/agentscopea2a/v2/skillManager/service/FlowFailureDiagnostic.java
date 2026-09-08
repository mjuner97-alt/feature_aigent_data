package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** Browser-safe long-task failure details correlated with the full server-side stack trace. */
record FlowFailureDiagnostic(String errorId, String stage, String rootType,
                             String rootMessage, String location, Long flowId,
                             Long nodeId, String nodeName) {

    private static final String APPLICATION_PACKAGE = "com.agentscopea2a";

    static FlowFailureDiagnostic capture(Throwable error, String stage, Long flowId,
                                         SkillFlowNodeExecution node) {
        Throwable root = deepestCause(Objects.requireNonNull(error, "error"));
        StackTraceElement frame = Arrays.stream(root.getStackTrace())
                .filter(item -> item.getClassName().startsWith(APPLICATION_PACKAGE))
                .findFirst()
                .orElse(root.getStackTrace().length == 0 ? null : root.getStackTrace()[0]);
        String location = frame == null ? "未知位置"
                : simpleClassName(frame.getClassName()) + "." + frame.getMethodName()
                + ":" + frame.getLineNumber();
        String nodeName = node == null ? "未命名节点"
                : Stream.of(node.getSkillName(), node.getSkillRetrievalName(), node.getNodeKey())
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("未命名节点");
        String message = root.getMessage() == null || root.getMessage().isBlank()
                ? "无异常消息" : root.getMessage();
        return new FlowFailureDiagnostic(
                "FLOW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                stage == null || stage.isBlank() ? "UNKNOWN" : stage,
                root.getClass().getSimpleName(), message, location, flowId,
                node == null ? null : node.getId(), nodeName);
    }

    String displayMessage() {
        return String.join("\n",
                "错误编号: " + errorId,
                "失败阶段: " + stage,
                "根因: " + rootType + ": " + rootMessage,
                "定位: " + location,
                "流程ID: " + Objects.toString(flowId, "-"),
                "节点ID: " + Objects.toString(nodeId, "-"),
                "节点: " + nodeName);
    }

    private static Throwable deepestCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String simpleClassName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }
}
