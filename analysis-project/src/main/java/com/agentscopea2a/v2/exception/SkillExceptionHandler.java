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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for the Skill management API.
 *
 * <p>Translates the dedicated v2 exceptions (see §12.6) and the legacy
 * {@code IllegalStateException} message conventions into the appropriate HTTP status codes.
 *
 * <p>所有错误响应体统一为 JSON {@code {"message": "..."}} - 前端 api/skill.ts / skillJob.ts /
 * modelConfig.ts 的 error helper 都按 {@code body.message || body.error} 解析,纯文本会让
 * {@code res.json()} 抛错、走 fallback,导致 SkillNameConflict -> "名称已存在" 这类映射失效。
 */
@RestControllerAdvice
public class SkillExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillExceptionHandler.class);

    @ExceptionHandler(DraftAlreadyPendingException.class)
    public ResponseEntity<Map<String, String>> handleDraftAlreadyPending(DraftAlreadyPendingException ex) {
        log.warn("DraftAlreadyPending: {}", ex.getMessage());
        return jsonBody(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DraftNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleDraftNotFound(DraftNotFoundException ex) {
        log.warn("DraftNotFound: {}", ex.getMessage());
        return jsonBody(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NotApproverException.class)
    public ResponseEntity<Map<String, String>> handleNotApprover(NotApproverException ex) {
        log.warn("NotApprover: {}", ex.getMessage());
        return jsonBody(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(PublishAlreadyApprovedException.class)
    public ResponseEntity<Map<String, String>> handlePublishAlreadyApproved(PublishAlreadyApprovedException ex) {
        log.warn("PublishAlreadyApproved: {}", ex.getMessage());
        return jsonBody(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage();
        HttpStatus status = resolveIllegalStateStatus(message);
        log.warn("IllegalState mapped to {}: {}", status, message);
        return jsonBody(status, message);
    }

    private ResponseEntity<Map<String, String>> jsonBody(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message == null ? "" : message));
    }

    private HttpStatus resolveIllegalStateStatus(String message) {
        if (message == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (message.startsWith("SkillNotFound")) {
            return HttpStatus.NOT_FOUND;
        }
        if (message.startsWith("SkillAccessDenied")) {
            return HttpStatus.FORBIDDEN;
        }
        if (message.startsWith("SkillNameConflict")) {
            return HttpStatus.CONFLICT;
        }
        if (message.startsWith("SkillPendingApproval")) {
            return HttpStatus.CONFLICT;
        }
        // SkillJob 相关异常前缀
        if (message.startsWith("JobNotFound")) {
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
