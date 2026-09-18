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
package com.agentscopea2a.v2.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理器: 拦截所有 REST 控制器抛出的异常, 统一翻译为 HTTP 状态码 + JSON 错误体.
 *
 * <p>覆盖两类异常来源:
 * <ul>
 *   <li>v2 专用异常类型 (本包下, 见方案 §12.6) - 直接按异常类型映射状态码;</li>
 *   <li>遗留 {@link IllegalStateException} 消息前缀约定 (如 {@code SkillNotFound: xxx}) -
 *       由 {@link #resolveIllegalStateStatus} 按前缀解析出 404/403/409/503 等,
 *       未命中前缀的兜底为 500。</li>
 * </ul>
 *
 * <p>所有错误响应体统一为 JSON {@code {"message": "..."}} - 前端 api/skill.ts / skillJob.ts /
 * modelConfig.ts 的 error helper 都按 {@code body.message || body.error} 解析,纯文本会让
 * {@code res.json()} 抛错、走 fallback,导致 SkillNameConflict -> "名称已存在" 这类映射失效。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 保证本处理器优先于 Spring 默认的
 * {@code ErrorMvcAutoConfiguration} 兜底, 否则自定义异常会被吞成白页/500。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 草稿已有待审批单, 重复提交创建/审批 -> 409 CONFLICT。 */
    @ExceptionHandler(DraftAlreadyPendingException.class)
    public ResponseEntity<Map<String, String>> handleDraftAlreadyPending(DraftAlreadyPendingException ex) {
        log.warn("DraftAlreadyPending: {}", ex.getMessage());
        return jsonBody(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 操作的草稿不存在 (或已删除) -> 404 NOT_FOUND。 */
    @ExceptionHandler(DraftNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleDraftNotFound(DraftNotFoundException ex) {
        log.warn("DraftNotFound: {}", ex.getMessage());
        return jsonBody(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 当前用户不是该草稿的审批人 -> 403 FORBIDDEN。 */
    @ExceptionHandler(NotApproverException.class)
    public ResponseEntity<Map<String, String>> handleNotApprover(NotApproverException ex) {
        log.warn("NotApprover: {}", ex.getMessage());
        return jsonBody(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** 审批单已通过, 重复审批 -> 409 CONFLICT。 */
    @ExceptionHandler(PublishAlreadyApprovedException.class)
    public ResponseEntity<Map<String, String>> handlePublishAlreadyApproved(PublishAlreadyApprovedException ex) {
        log.warn("PublishAlreadyApproved: {}", ex.getMessage());
        return jsonBody(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 参数校验失败 (非法入参/格式错误) -> 400 BAD_REQUEST。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request argument: {}", ex.getMessage());
        return jsonBody(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 遗留业务异常: 按 message 前缀解析状态码 (见 {@link #resolveIllegalStateStatus})。
     * 前缀映射为 4xx 时记 warn 足够; 映射为 5xx 说明是约定外的异常状态, 记 error 带全栈便于排查。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage();
        HttpStatus status = resolveIllegalStateStatus(message);
        if (status.is5xxServerError()) {
            log.error("Unexpected IllegalStateException", ex);
        } else {
            log.warn("IllegalState mapped to {}: {}", status, message);
        }
        return jsonBody(status, message);
    }

    /** 触发限流 (如 webhook/执行接口) -> 429 TOO_MANY_REQUESTS。 */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Too many requests: {}", ex.getMessage());
        return jsonBody(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    /**
     * 创建/更新 Skill 命中高相似描述: 409 + 结构化相似列表 (含 ownerUserId),
     * 前端转"已存在相近描述"提示框, 用户仅能返回修改 (无放行路径)。
     * 必须放在本类 (HIGHEST_PRECEDENCE): 否则被下方 Exception 兜底吃掉变 500。
     */
    @ExceptionHandler(SkillDescriptionSimilarException.class)
    public ResponseEntity<Map<String, Object>> handleDescriptionSimilar(SkillDescriptionSimilarException ex) {
        log.warn("SkillDescriptionSimilar: matches={}", ex.result().matches().size());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "SkillDescriptionSimilar",
                        "message", "已存在相近描述的 Skill, 请修改名称或描述后重试 (联系人见 matches[].ownerUserId)",
                        "degraded", ex.result().degraded(),
                        "matches", ex.result().matches()));
    }

    /**
     * 兜底处理器: 未能精确匹配的未知异常 -> 500。
     * 生成随机 errorId 一并写入日志与响应体, 便于用户反馈后按 errorId 反查具体堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception: errorId={}, method={}, uri={}",
                errorId, request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "message", ex.getMessage(),
                        "errorId", errorId));
    }

    /** 统一构造 {@code {"message": ...}} JSON 错误体 (message 为 null 时置空串, 防 NPE)。 */
    private ResponseEntity<Map<String, String>> jsonBody(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message == null ? "" : message));
    }

    /**
     * 遗留异常约定: IllegalStateException 的 message 以 {@code 前缀: 详情} 形式携带业务错误码,
     * 按前缀映射 HTTP 状态码。新增业务错误码时在此追加分支, 未命中前缀一律兜底 500。
     */
    private HttpStatus resolveIllegalStateStatus(String message) {
        // message 为 null 无法解析前缀, 直接按未知异常处理
        if (message == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // ---- Skill 管理相关前缀 ----
        if (message.startsWith("SkillNotFound")) {
            return HttpStatus.NOT_FOUND;
        }
        if (message.startsWith("SkillAccessDenied")) {
            return HttpStatus.FORBIDDEN;
        }
        if (message.startsWith("ResourceAccessDenied")) {
            return HttpStatus.FORBIDDEN;
        }
        if (message.startsWith("SkillNameConflict")) {
            return HttpStatus.CONFLICT;
        }
        if (message.startsWith("SkillPendingApproval")) {
            return HttpStatus.CONFLICT;
        }
        if (message.startsWith("NoDeveloperReviewerConfigured")) {
            return HttpStatus.CONFLICT;
        }
        // SkillJob 相关异常前缀
        if (message.startsWith("JobNotFound")) {
            return HttpStatus.NOT_FOUND;
        }
        if (message.startsWith("FlowReportNotFound") || message.startsWith("FlowNodeReportNotFound")
                || message.startsWith("FlowExecutionNotFound")) {
            return HttpStatus.NOT_FOUND;
        }
        if (message.startsWith("JobAccessDenied")) {
            return HttpStatus.FORBIDDEN;
        }
        if (message.startsWith("JobNameConflict") || message.startsWith("JobAlreadyRunning")) {
            return HttpStatus.CONFLICT;
        }
        if (message.startsWith("JobQueueFull")) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (message.startsWith("WebhookAuthFailed")) {
            return HttpStatus.UNAUTHORIZED;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
