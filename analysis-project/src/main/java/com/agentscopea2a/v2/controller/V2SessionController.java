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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.util.PermissionModeHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * User-facing session-level operations.
 *
 * <p>Hosts the migrated {@code /permission/mode} endpoints (previously under {@code /debug/}).
 * The debug paths remain functional but emit RFC 7234 deprecation headers pointing here -
 * see {@code DebugController}.
 *
 * <ul>
 *   <li>{@code GET /v2/ai/session/permission/mode?userId=X&sessionId=Y} - get current mode
 *   <li>{@code POST /v2/ai/session/permission/mode?userId=X&sessionId=Y&mode=bypass} - switch mode
 * </ul>
 *
 * <p>Valid modes: {@code default}, {@code accept_edits}, {@code explore}, {@code bypass},
 * {@code dont_ask}. Use {@code bypass} to auto-approve HITL prompts (e.g. {@code plan_exit}
 * approval) for E2E tests that need execution to continue without manual approval.
 *
 * <p>The actual logic lives in {@link PermissionModeHelper} so the deprecated
 * {@code /debug/permission/mode} endpoint stays behaviorally identical during migration.
 */
@RestController
@RequestMapping("/v2/ai/session")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2SessionController {

    private final PermissionModeHelper permissionModeHelper;

    public V2SessionController(PermissionModeHelper permissionModeHelper) {
        this.permissionModeHelper = permissionModeHelper;
    }

    @GetMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> getPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId) {
        return permissionModeHelper.getPermissionMode(userId, sessionId);
    }

    @PostMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> setPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("mode") String modeStr) {
        return permissionModeHelper.setPermissionMode(userId, sessionId, modeStr);
    }
}
