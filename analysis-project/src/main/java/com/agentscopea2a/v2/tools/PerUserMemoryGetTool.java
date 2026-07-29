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
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Per-user isolated replacement for the framework's {@code MemoryGetTool}.
 *
 * <p>Reads {@code MEMORY.md} and daily ledger files from the per-user DB
 * ({@link MysqlMemoryStore}) instead of the shared workspace filesystem. This
 * prevents cross-tenant data leakage when a new user calls
 * {@code memory_get("MEMORY.md")} and would otherwise see a previous user's
 * curated memory from the shared root {@code MEMORY.md}.
 *
 * <p>The framework's {@code WorkspaceManager.readWithOverride()} has a two-layer
 * pattern: filesystem layer (namespaced by IsolationScope) first, then local disk
 * fallback to {@code workspace.resolve("MEMORY.md")} (shared root). The sandbox
 * filesystem may not properly namespace root-level files, and the Docker container's
 * {@code /workspace/MEMORY.md} can be recreated by {@code memory_save} or framework
 * flush. This tool bypasses the filesystem entirely, reading only from the per-user
 * DB.
 *
 * <p>Posture: when {@code userId} is null/blank, return an error rather than
 * falling back to any shared source - same posture as
 * {@link com.agentscopea2a.v2.middleware.PerUserMemoryContextMiddleware}.
 */
public class PerUserMemoryGetTool {

    private static final Logger log = LoggerFactory.getLogger(PerUserMemoryGetTool.class);

    private static final String MEMORY_MD = "MEMORY.md";
    private static final String MEMORY_DIR_PREFIX = "memory/";
    private static final String MD_SUFFIX = ".md";

    private final MysqlMemoryStore mysqlMemoryStore;

    public PerUserMemoryGetTool(MysqlMemoryStore mysqlMemoryStore) {
        this.mysqlMemoryStore = mysqlMemoryStore;
    }

    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "Read specific lines from a memory file. Use after memory_search to pull"
                            + " full context around matched lines. Path is relative to workspace.")
    public String memoryGet(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "path",
                            description =
                                    "Relative path to the memory file (e.g., MEMORY.md or"
                                            + " memory/2026-04-01.md)")
                    String path,
            @ToolParam(name = "startLine", description = "Start line number (1-based, inclusive)")
                    int startLine,
            @ToolParam(name = "endLine", description = "End line number (1-based, inclusive)")
                    int endLine) {
        if (path == null || path.isBlank()) {
            return "Error: path is required";
        }

        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        if (userId == null || userId.isBlank()) {
            return "Error: user not identified - cannot read per-user memory";
        }

        String text = readPerUserMemory(userId, path);
        if (text == null || text.isEmpty()) {
            return "Error: file not found: " + path;
        }

        List<String> lines = List.of(text.split("\n", -1));
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.size(), endLine);

        if (start >= lines.size()) {
            return "Error: startLine " + startLine + " exceeds file length " + lines.size();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%d|%s%n", i + 1, lines.get(i)));
        }
        return sb.toString();
    }

    private String readPerUserMemory(String userId, String path) {
        try {
            if (MEMORY_MD.equals(path) || "MEMORY".equals(path)) {
                return mysqlMemoryStore
                        .read(userId, MysqlMemoryStore.KIND_MEMORY_MD, MEMORY_MD)
                        .orElse("");
            }
            if (path.startsWith(MEMORY_DIR_PREFIX) && path.endsWith(MD_SUFFIX)) {
                String dateKey = path.substring(
                        MEMORY_DIR_PREFIX.length(), path.length() - MD_SUFFIX.length());
                List<MysqlMemoryStore.LedgerRow> rows =
                        mysqlMemoryStore.readLedgerForDate(userId, dateKey);
                if (rows.isEmpty()) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                for (MysqlMemoryStore.LedgerRow row : rows) {
                    sb.append(row.line()).append('\n');
                }
                return sb.toString();
            }
            log.debug("PerUserMemoryGetTool: unsupported path '{}' (only MEMORY.md and memory/YYYY-MM-DD.md)", path);
            return "";
        } catch (Exception e) {
            log.warn("PerUserMemoryGetTool: failed to read memory for user={} path={}: {}",
                    userId, path, e.getMessage());
            return "";
        }
    }
}
