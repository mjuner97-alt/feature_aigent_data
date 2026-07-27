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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for the Skill management API.
 *
 * <p>Translates the dedicated v2 exceptions (see §12.6) and the legacy
 * {@code IllegalStateException} message conventions into the appropriate HTTP status codes.
 */
@RestControllerAdvice
public class SkillExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillExceptionHandler.class);

    @ExceptionHandler(DraftAlreadyPendingException.class)
    public ResponseEntity<String> handleDraftAlreadyPending(DraftAlreadyPendingException ex) {
        log.warn("DraftAlreadyPending: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(DraftNotFoundException.class)
    public ResponseEntity<String> handleDraftNotFound(DraftNotFoundException ex) {
        log.warn("DraftNotFound: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(NotApproverException.class)
    public ResponseEntity<String> handleNotApprover(NotApproverException ex) {
        log.warn("NotApprover: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(PublishAlreadyApprovedException.class)
    public ResponseEntity<String> handlePublishAlreadyApproved(PublishAlreadyApprovedException ex) {
        log.warn("PublishAlreadyApproved: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage();
        HttpStatus status = resolveIllegalStateStatus(message);
        log.warn("IllegalState mapped to {}: {}", status, message);
        return ResponseEntity.status(status).body(message);
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
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
