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
package com.agentscopea2a.v2.util;

import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared logic for getting/setting the per-session {@link PermissionMode}.
 *
 * <p>Used by both the user-facing {@code /v2/ai/session/permission/mode} endpoint
 * (in {@code V2SessionController}) and the deprecated {@code /debug/permission/mode}
 * endpoint (in {@code DebugController}) so the two paths stay behaviorally identical
 * while the migration is in flight.
 *
 * <p>Plan B revision #8 - extracted to a helper so the deprecation headers on the
 * debug endpoint don't entangle with the mode-switching logic. The helper is a
 * Spring {@code @Component} so both controllers get the same instance.
 */
@Component
public class PermissionModeHelper {

    private static final Logger log = LoggerFactory.getLogger(PermissionModeHelper.class);

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;

    public PermissionModeHelper(ObjectProvider<HarnessA2aRunnerV2> runnerProvider) {
        this.runnerProvider = runnerProvider;
    }

    /**
     * Returns the current {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * <p>Mode is persisted in {@code agent_state.permission_context.mode} as part of the
     * session state. Available modes: {@code default}, {@code accept_edits}, {@code explore},
     * {@code bypass}, {@code dont_ask}.
     */
    public ResponseEntity<Map<String, Object>> getPermissionMode(String userId, String sessionId) {
        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "HarnessA2aRunnerV2 bean not available");
        }
        try {
            HarnessAgent agent = runner.getAgent();
            PermissionMode mode = agent.getPermissionMode(userId, sessionId);
            Map<String, Object> out = new HashMap<>();
            out.put("userId", userId);
            out.put("sessionId", sessionId);
            out.put("mode", mode != null ? mode.getValue() : null);
            out.put("availableModes", Arrays.stream(PermissionMode.values())
                    .map(PermissionMode::getValue)
                    .toList());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("getPermissionMode failed for userId={},sessionId={}: {}",
                    userId, sessionId, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Switches the {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * <p>Use {@code bypass} to auto-approve HITL prompts (e.g. {@code plan_exit} approval)
     * for E2E tests that need execution to continue without manual approval.
     */
    public ResponseEntity<Map<String, Object>> setPermissionMode(String userId, String sessionId, String modeStr) {
        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "HarnessA2aRunnerV2 bean not available");
        }
        PermissionMode mode;
        try {
            mode = PermissionMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "Unknown mode: " + modeStr);
            err.put("availableModes", Arrays.stream(PermissionMode.values())
                    .map(PermissionMode::getValue)
                    .toList());
            return ResponseEntity.badRequest().body(err);
        }
        try {
            HarnessAgent agent = runner.getAgent();
            agent.setPermissionMode(userId, sessionId, mode);
            log.info("Permission mode switched: userId={}, sessionId={}, mode={}",
                    userId, sessionId, mode.getValue());
            Map<String, Object> out = new HashMap<>();
            out.put("status", "ok");
            out.put("userId", userId);
            out.put("sessionId", sessionId);
            out.put("mode", mode.getValue());
            out.put("timestamp", java.time.LocalDateTime.now().toString());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("setPermissionMode failed for userId={},sessionId={},mode={}: {}",
                    userId, sessionId, modeStr, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", message);
        return ResponseEntity.status(status).body(err);
    }
}
