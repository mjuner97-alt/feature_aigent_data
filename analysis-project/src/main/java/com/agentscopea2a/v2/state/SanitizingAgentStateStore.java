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
package com.agentscopea2a.v2.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps an {@link AgentStateStore} to sanitize sessionIds by replacing {@code '/'} with
 * {@code ':'} before delegating to the underlying store.
 *
 * <p>Workaround for a JAR-level inconsistency between {@code SessionSandboxStateStore} and
 * {@code MysqlAgentStateStore} (both in agentscope 2.0.0-RC5):
 * <ul>
 *   <li>{@code SessionSandboxStateStore.slotSessionId()} constructs sessionIds like
 *       {@code "sandbox/session/<value>"} using {@code '/'} as the scope separator.
 *   <li>{@code MysqlAgentStateStore.validateSessionId(line 815)} rejects any sessionId
 *       containing {@code '/'} or {@code '\\'} with
 *       {@code IllegalArgumentException: AgentStateStore ID cannot contain path separators}.
 * </ul>
 *
 * <p>Without this wrapper, sandbox state never persists to MySQL — every request falls
 * through to a fresh sandbox create. The {@code ':'} separator is explicitly allowed by
 * {@code MysqlAgentStateStore} (see line 705 comment:
 * "Uses : as the separator so existing validateSessionId (which rejects /) ...").
 *
 * <p>SessionIds that do not contain {@code '/'} (e.g. {@code "anonymous:e2e-c2-final-001"})
 * pass through unchanged, so non-sandbox state is unaffected.
 */
public class SanitizingAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;

    public SanitizingAgentStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    private static String sanitize(String sessionId) {
        return sessionId == null ? null : sessionId.replace('/', ':');
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(userId, sanitize(sessionId), key, value);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(userId, sanitize(sessionId), key, values);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sanitize(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, sanitize(sessionId), key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sanitize(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sanitize(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sanitize(sessionId), key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
