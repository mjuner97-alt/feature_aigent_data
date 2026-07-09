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
package com.agentscopea2a.controller;

import com.agentscopea2a.agent.memory.digestion.MemoryDigestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual trigger endpoint for the night-time digestion pipeline.
 * Only active when {@code harness.a2a.memory.digestion.enabled=true}.
 *
 * <p>Usage: {@code POST /api/digestion/run} with body {@code {"user_id":"u-test1"}}
 */
@RestController
@RequestMapping("/api/digestion")
@ConditionalOnProperty(
        prefix = "harness.a2a.memory.digestion",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class DigestionController {

    private static final Logger log = LoggerFactory.getLogger(DigestionController.class);

    private final ObjectProvider<MemoryDigestionService> digestionServiceProvider;

    public DigestionController(ObjectProvider<MemoryDigestionService> digestionServiceProvider) {
        this.digestionServiceProvider = digestionServiceProvider;
    }

    @PostMapping("/run")
    public Map<String, Object> triggerDigest(@RequestBody(required = false) Map<String, String> body) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        MemoryDigestionService svc = digestionServiceProvider.getIfAvailable();
        if (svc == null) {
            out.put("status", "disabled");
            out.put("message", "MemoryDigestionService bean not available");
            return out;
        }
        long start = System.currentTimeMillis();
        try {
            // If user_id is provided, run digestion for that user only
            // Otherwise, the scheduled digest() runs for all active users
            if (body != null && body.containsKey("user_id")) {
                // We call the public digest() which runs for all users;
                // For a single user, we'd need a different method. For now,
                // just run the full pipeline.
                log.info("Manual digestion trigger requested for user_id={}", body.get("user_id"));
            }
            svc.digest();
            out.put("status", "ok");
            out.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", e.getMessage());
            log.error("Manual digest failed: {}", e.getMessage(), e);
        }
        out.put("timestamp", java.time.LocalDateTime.now().toString());
        return out;
    }
}