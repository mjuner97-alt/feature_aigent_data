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
package com.agentscopea2a.v2.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Session-aware traffic router between v1 and v2 entry points.
 *
 * <p>Provides percentage-based gray-switch routing with session stickiness:
 * once a conversation is routed to v1 or v2, it stays on that path for its
 * entire lifetime. New conversations are routed based on the configured
 * percentage.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code harness.routing.v2-percentage=100} — percentage of new sessions
 *       routed to v2 (100 = all v2, 0 = all v1, 50 = 50/50 split).</li>
 * </ul>
 *
 * <p>In Stage 7, the default is 100 (all v2). The router becomes active when
 * v1 controllers are re-enabled for rollback in Stage 8+.
 */
@Component
public class V2SessionRouter {

    private static final Logger log = LoggerFactory.getLogger(V2SessionRouter.class);

    private final int v2Percentage;

    /** Sticky routing map: conversationId → true (v2) / false (v1). */
    private final ConcurrentHashMap<String, Boolean> sessionRouting = new ConcurrentHashMap<>();

    public V2SessionRouter(
            @Value("${harness.routing.v2-percentage:100}") int v2Percentage) {
        this.v2Percentage = Math.max(0, Math.min(100, v2Percentage));
        log.info("V2SessionRouter: v2Percentage={} (100=all v2, 0=all v1)", this.v2Percentage);
    }

    /**
     * Determine whether a request should be routed to v2.
     *
     * <p>If the conversation has been seen before, returns the cached routing decision
     * (session stickiness). Otherwise, makes a new routing decision based on the
     * configured percentage.
     *
     * @param conversationId the conversation ID
     * @return true if the request should be handled by v2, false for v1
     */
    public boolean shouldUseV2(String conversationId) {
        if (v2Percentage >= 100) return true;
        if (v2Percentage <= 0) return false;

        return sessionRouting.computeIfAbsent(conversationId, id ->
                ThreadLocalRandom.current().nextInt(100) < v2Percentage);
    }

    /**
     * Remove a session's routing decision. Called when a session ends or expires.
     *
     * @param conversationId the conversation ID to remove
     */
    public void removeSession(String conversationId) {
        sessionRouting.remove(conversationId);
    }

    /**
     * Get the number of tracked sessions (for monitoring).
     */
    public int getSessionCount() {
        return sessionRouting.size();
    }

    /**
     * Get the configured v2 percentage.
     */
    public int getV2Percentage() {
        return v2Percentage;
    }
}