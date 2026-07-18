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
package com.agentscopea2a.v2.digestion;

import com.agentscopea2a.v2.memory.MemoryHydrator;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.ToolCallDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase 4 of the nightly digestion pipeline: consolidates a user's successful tool-call traces
 * into their per-user MEMORY.md via LLM summarisation.
 *
 * <p>Reads the user's current MEMORY.md from {@link MysqlMemoryStore}, calls the LLM to merge
 * today's successful query patterns into the "常用流程" section, then writes back via upsert and
 * triggers {@link MemoryHydrator#hydrate} to update the workspace file.
 *
 * <p>After a successful consolidation, the {@code .consolidation_state} watermark is updated so
 * the harness's built-in {@code MemoryConsolidator} doesn't re-process already-digested entries.
 */
public class MemoryFlowConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlowConsolidator.class);

    private static final String CONSOLIDATION_STATE_FILE = ".consolidation_state";

    private final MysqlMemoryStore store;
    private final MemoryHydrator hydrator;
    private final Model model;
    private final Path workspaceMemoryRoot;

    public MemoryFlowConsolidator(MysqlMemoryStore store, MemoryHydrator hydrator,
                                  Model model, Path workspaceMemoryRoot) {
        this.store = store;
        this.hydrator = hydrator;
        this.model = model;
        this.workspaceMemoryRoot = workspaceMemoryRoot;
    }

    /**
     * Consolidate today's successful traces into the user's MEMORY.md.
     *
     * @param userId       the target user
     * @param traces       successful trace summaries (those worth recording)
     * @return true when the MEMORY.md was actually updated
     */
    public boolean consolidate(String userId, List<SkillFlowEvolver.TraceSummary> traces) {
        if (userId == null || userId.isBlank()) return false;
        if (traces == null || traces.isEmpty()) return false;

        // Only consider traces with non-zero success count
        List<SkillFlowEvolver.TraceSummary> goodTraces = traces.stream()
                .filter(t -> t != null && t.successCount() > 0)
                .toList();
        if (goodTraces.isEmpty()) return false;

        // Read the current MEMORY.md from DB
        String currentMd = store.read(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md")
                .orElse("");

        // Build a consolidation prompt with today's successful patterns
        String prompt = buildConsolidationPrompt(currentMd, goodTraces);
        if (prompt == null) return false;

        // Call LLM
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
        String newBody = model.stream(List.of(userMsg), List.of(), null)
                .reduce(new StringBuilder(), (acc, resp) -> {
                    if (resp != null && resp.getContent() != null) {
                        resp.getContent().stream()
                                .filter(cb -> cb instanceof TextBlock)
                                .map(cb -> ((TextBlock) cb).getText())
                                .forEach(t -> acc.append(t == null ? "" : t));
                    }
                    return acc;
                })
                .map(StringBuilder::toString)
                .block();

        if (newBody == null || newBody.isBlank()) {
            log.info("Consolidation produced empty output for user={}; skipping", userId);
            return false;
        }

        // If the LLM returned the same content as current, skip writing
        if (newBody.equals(currentMd)) {
            log.info("Consolidation produced no effective change for user={}; skipping", userId);
            return false;
        }

        // Write back via upsert
        try {
            store.upsert(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md", newBody);
            // Hydrate the workspace file so next request sees the updated MEMORY.md
            hydrator.hydrate(userId);
            // Update .consolidation_state watermark so MemoryConsolidator skips already-digested entries
            updateConsolidationWatermark(userId);
            log.info("Consolidated MEMORY.md for user={} ({} bytes)", userId, newBody.length());
            return true;
        } catch (Exception e) {
            log.warn("Consolidation upsert failed for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Build the LLM prompt that merges today's successful tool traces into the existing MEMORY.md.
     * Returns null when there's nothing worth consolidating.
     */
    private String buildConsolidationPrompt(String currentMd, List<SkillFlowEvolver.TraceSummary> traces) {
        if (currentMd == null) currentMd = "";

        StringBuilder sb = new StringBuilder();
        sb.append("你正在整理一位研发管理用户的记忆文件(MEMORY.md)。");
        sb.append("以下是该用户今天的成功查询流程(工具调用序列):\n\n");

        for (SkillFlowEvolver.TraceSummary t : traces) {
            // Prefer runtimeFingerprint for human readability (metric-based, e.g. "defect_density")
            // Falls back to tool-sequence fingerprint for legacy rows without runtimeFingerprint
            String displayFp = (t.runtimeFingerprint() != null && !t.runtimeFingerprint().isBlank())
                    ? t.runtimeFingerprint() : t.fingerprint();
            sb.append("- **模式**: ").append(displayFp).append('\n');
            sb.append("  **工具链**: ").append(t.toolSequence()).append('\n');
            sb.append("  **成功次数**: ").append(t.successCount()).append('\n');
            if (t.userQuery() != null && !t.userQuery().isBlank()) {
                sb.append("  **用户完整提问**: ").append(t.userQuery()).append('\n');
            } else if (t.sampleQuery() != null && !t.sampleQuery().isBlank()) {
                sb.append("  **用户问法**: ").append(t.sampleQuery()).append('\n');
            }
            // Append tool call details if available
            String detailsStr = renderToolCallDetails(t.toolCallDetails());
            if (!detailsStr.isEmpty()) {
                sb.append("  **工具调用详情**:\n").append(detailsStr);
            }
            sb.append('\n');
        }

        sb.append("请将以上成功流程合并到用户的 MEMORY.md 的对应章节(如'常用流程'/'查询示例')。\n");
        sb.append("要求:\n");
        sb.append("1. 保持原有的 MEMORY.md 格式和内容\n");
        sb.append("2. 新增的成功流程添加在合适的位置，不要删除已有内容\n");
        sb.append("3. 用中文描述每个流程的用途、适用场景、关键参数\n");
        sb.append("4. 如果某个流程已在 MEMORY.md 中，可以略过\n\n");
        sb.append("当前 MEMORY.md 内容:\n\n");
        sb.append(currentMd.isBlank() ? "(空)" : currentMd);

        return sb.toString();
    }

    /**
     * Render tool call details JSON into concise text lines for the consolidation prompt.
     */
    private String renderToolCallDetails(String toolCallDetailsJson) {
        if (toolCallDetailsJson == null || toolCallDetailsJson.isBlank()) return "";
        try {
            List<ToolCallDetail> details = new ObjectMapper()
                    .readValue(toolCallDetailsJson, new TypeReference<List<ToolCallDetail>>() {});
            StringBuilder sb = new StringBuilder();
            int step = 1;
            for (ToolCallDetail d : details) {
                sb.append("    ").append(step).append(". [").append(d.level()).append("] ")
                        .append(d.tool());
                if (d.input() != null && !d.input().isBlank()) {
                    sb.append("(").append(d.input()).append(")");
                }
                sb.append("\n");
                step++;
            }
            String result = sb.toString();
            return result.isBlank() ? "" : result;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Update the {@code .consolidation_state} watermark file so the harness's built-in
     * {@code MemoryConsolidator} knows how far we've processed. The file format matches the
     * existing convention: a single timestamp line representing the latest processed entry.
     */
    private void updateConsolidationWatermark(String userId) {
        try {
            Path userDir = workspaceMemoryRoot.resolve(userId);
            Files.createDirectories(userDir);
            Path stateFile = userDir.resolve(CONSOLIDATION_STATE_FILE);
            Files.writeString(stateFile, java.time.Instant.now().toString(),
                    StandardCharsets.UTF_8);
            log.debug("Updated consolidation watermark for user={}", userId);
        } catch (IOException e) {
            log.warn("Failed to update consolidation watermark for user={}: {}",
                    userId, e.getMessage());
        }
    }
}
