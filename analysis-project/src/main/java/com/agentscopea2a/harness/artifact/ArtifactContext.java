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
package com.agentscopea2a.harness.artifact;

import io.agentscope.core.agent.RuntimeContext;

/**
 * Per-request artifact-resolution context.
 *
 * <p>Carries everything {@link ArtifactStore} needs to compute a tenant-isolated artifact path:
 * the user bucket (sanitized {@code userId}, falling back to {@code _anon}) and the task bucket
 * (the A2A {@code sessionId} which equals the per-request taskId in this example's wiring).
 *
 * <p>This is a value object — never mutated after construction. {@link ArtifactStoreContext}
 * propagates it across the call stack via a {@link ThreadLocal} so {@code @Tool} methods don't
 * have to take it as a parameter (the tool annotation framework wouldn't be able to fill it in
 * anyway).
 */
public record ArtifactContext(String userBucket, String taskBucket) {

    /** Anonymous bucket — used when no {@code userId} is available on the request. */
    public static final String ANON_USER = "_anon";

    /** Default task bucket — used when the request has no sessionId / taskId. */
    public static final String DEFAULT_TASK = "_default";

    /**
     * Builds a context from a {@link RuntimeContext}. Both buckets are sanitized so a hostile
     * {@code userId} (e.g. {@code "../../etc"}) can't escape the per-tenant directory.
     */
    public static ArtifactContext from(RuntimeContext ctx) {
        if (ctx == null) {
            return new ArtifactContext(ANON_USER, DEFAULT_TASK);
        }
        String userId = ctx.getUserId();
        String userBucket = (userId == null || userId.isBlank()) ? ANON_USER : sanitize(userId);
        String sessionId = ctx.getSessionId();
        String taskBucket =
                (sessionId == null || sessionId.isBlank()) ? DEFAULT_TASK : sanitize(sessionId);
        return new ArtifactContext(userBucket, taskBucket);
    }

    /** Allow letters / digits / underscore / dash; everything else collapses to underscore. */
    static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
