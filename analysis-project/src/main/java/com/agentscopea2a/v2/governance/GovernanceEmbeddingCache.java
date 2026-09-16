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
package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 治理检测 (Skill-Tool 重叠 / Skill 描述相似) 共用的描述向量进程内缓存。
 *
 * <p>设计要点:
 * <ul>
 * <li>键 = (entityType, entityId, sha256(description)) —— 描述变更即新键, 天然免失效;
 *     旧键残留无害, 加 4096 条上限整体清空兜底。</li>
 * <li>SKILL 的 entityId 用 retrievalName (page_&lt;id&gt; 或内置名, 与 skill_index.name 一致),
 *     描述取 skill_manage.description —— 单一权威源; TOOL 用 toolId + tool_route_metadata.description。</li>
 * <li>无 embedding provider (当前 harness.embedding.* 未配置, OpenAiCompatEmbeddingClient
 *     不装配) 时 {@link #semanticAvailable()} 为 false, 调用方降级为纯文本信号。</li>
 * <li>启动后台异步预热, 不阻塞启动; 预热未完成期间 {@link #warm()} 为 false,
 *     调用方按 degraded 处理。单条 embed 失败 (null) 不写缓存, 下次调用重试。</li>
 * </ul>
 */
public class GovernanceEmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(GovernanceEmbeddingCache.class);

    public enum EntityType {
        SKILL,
        TOOL
    }

    private static final int MAX_ENTRIES = 4096;

    private final EmbeddingClient embeddingClient;
    private final SkillDescriptionSource skillDescriptionSource;
    private final ToolRoutingMetadataRepository toolRoutingMetadataRepository;
    private final ConcurrentHashMap<String, float[]> vectors = new ConcurrentHashMap<>();
    private volatile boolean warm;

    public GovernanceEmbeddingCache(
            EmbeddingClient embeddingClient,
            SkillDescriptionSource skillDescriptionSource,
            ToolRoutingMetadataRepository toolRoutingMetadataRepository) {
        this.embeddingClient = embeddingClient;
        this.skillDescriptionSource = skillDescriptionSource;
        this.toolRoutingMetadataRepository = toolRoutingMetadataRepository;
    }

    @PostConstruct
    void startWarmup() {
        if (embeddingClient == null) {
            warm = true;
            return;
        }
        Thread t = new Thread(this::warmup, "governance-embedding-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** 语义信号当前是否可用 (取决于 embedding provider 是否装配)。 */
    public boolean semanticAvailable() {
        return embeddingClient != null;
    }

    /** 预热是否已完成。无 provider 视为完成 (调用方本就不会走语义路径)。 */
    public boolean warm() {
        return warm;
    }

    /**
     * 取描述向量, 缓存未命中时同步 embed 一次。任何失败 (无 provider / 空描述 /
     * embed 返回 null) 返回 null, 调用方按降级处理。
     */
    public float[] embeddingFor(EntityType type, String entityId, String description) {
        if (embeddingClient == null || entityId == null || description == null || description.isBlank()) {
            return null;
        }
        String key = type + "|" + entityId + "|" + sha256(description);
        float[] cached = vectors.get(key);
        if (cached != null) {
            return cached;
        }
        float[] vector = embeddingClient.embed(description);
        if (vector == null) {
            return null;
        }
        if (vectors.size() >= MAX_ENTRIES) {
            vectors.clear();
        }
        vectors.put(key, vector);
        return vector;
    }

    private void warmup() {
        try {
            int skills = 0;
            for (SkillDescriptionSource.SkillDescriptionRow row : skillDescriptionSource.allActiveSkills()) {
                if (embeddingFor(EntityType.SKILL, row.retrievalName(), row.description()) != null) {
                    skills++;
                }
            }
            int tools = 0;
            if (toolRoutingMetadataRepository != null) {
                List<ToolRoutingMetadata> enabled = toolRoutingMetadataRepository.findEnabled();
                for (ToolRoutingMetadata meta : enabled) {
                    if (embeddingFor(EntityType.TOOL, meta.toolId(), meta.description()) != null) {
                        tools++;
                    }
                }
            }
            log.info("Governance embedding warmup done: {} skills, {} tools", skills, tools);
        } catch (Exception e) {
            log.warn("Governance embedding warmup failed: {}", e.getMessage());
        } finally {
            warm = true;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
