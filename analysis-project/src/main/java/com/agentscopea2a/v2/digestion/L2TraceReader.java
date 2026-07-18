/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.digestion;

import com.agentscopea2a.v2.digestion.TraceMinerTypes.ParseL2Result;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.SubAgentSpawn;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.ToolCallDetail;
import com.agentscopea2a.v2.digestion.TraceMinerTypes.Truncation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads L2 subagent {@code memory_messages.jsonl} files and extracts tool-call sequences.
 * Extracted from {@code TraceMiner} (P2-1).
 *
 * <p>Each subagent spawn detected by {@code TraceMiner.buildSession} maps to a file at:
 * <pre>
 *   workspace/agents/{agentId}/context/sub-{subSessionId}/memory_messages.jsonl
 * </pre>
 *
 * <p>When the file is missing or unreadable, the reader returns an empty result -
 * the caller falls back to L1-only trace data (graceful degradation).
 */
final class L2TraceReader {

    private static final Logger log = LoggerFactory.getLogger(L2TraceReader.class);

    /** Max chars for tool output snippet (output can be huge, input is what matters). */
    static final int OUTPUT_MAX_LEN = 800;
    static final int INPUT_MAX_LEN = 800;

    private final Path workspaceRoot;
    private final ObjectMapper objectMapper;

    L2TraceReader(Path workspaceRoot, ObjectMapper objectMapper) {
        this.workspaceRoot = workspaceRoot;
        this.objectMapper = objectMapper;
    }

    /**
     * Read subagent memory_messages.jsonl files and extract L2 tool call names + details.
     * Returns deduplicated tool names preserving insertion order + full detail records.
     * Returns empty ParseL2Result if all files are missing/unreadable (graceful degradation to L1-only).
     */
    ParseL2Result readSubAgentToolCalls(List<SubAgentSpawn> spawns) {
        List<String> allToolNames = new ArrayList<>();
        List<ToolCallDetail> allDetails = new ArrayList<>();
        for (SubAgentSpawn spawn : spawns) {
            Path l2File = workspaceRoot
                    .resolve("agents")
                    .resolve(spawn.agentId())
                    .resolve("context")
                    .resolve(spawn.subSessionId())
                    .resolve("memory_messages.jsonl");
            if (!Files.exists(l2File)) {
                log.debug("L2 file not found (graceful degradation to L1): {}", l2File);
                continue;
            }
            try {
                ParseL2Result result = parseL2ToolCalls(l2File, spawn.agentId());
                allToolNames.addAll(result.toolNames());
                allDetails.addAll(result.details());
                log.debug("L2: extracted {} tool(s) + {} detail(s) from {}",
                        result.toolNames().size(), result.details().size(), l2File);
            } catch (Exception e) {
                log.debug("L2 file read failed (graceful degradation to L1): {} - {}",
                        l2File, e.getMessage());
            }
        }
        List<String> distinct = allToolNames.stream().distinct().collect(Collectors.toList());
        return new ParseL2Result(distinct, allDetails);
    }

    /**
     * Parse a single memory_messages.jsonl file, extracting tool_use and tool_result names
     * along with their input/output context.
     *
     * <p>Each line is a JSON message like:
     * <pre>
     * {"role":"ASSISTANT","content":[{"type":"tool_use","name":"toolMetaInfo","input":{...}}]}
     * {"role":"TOOL","content":[{"type":"tool_result","name":"toolMetaInfo","output":[...]}]}
     * </pre>
     *
     * <p>Input is kept up to {@link #INPUT_MAX_LEN} chars, output to {@link #OUTPUT_MAX_LEN}.
     * For tool_result, only the first line of output is kept - full output (e.g. entire
     * SKILL.md content from load_skill_through_path) drowns out the tool name and input
     * params that the LLM actually needs to reconstruct the correct tool chain.
     */
    private ParseL2Result parseL2ToolCalls(Path l2File, String agentId) throws IOException {
        List<String> tools = new ArrayList<>();
        List<ToolCallDetail> details = new ArrayList<>();
        List<String> lines = Files.readAllLines(l2File);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                JsonNode content = node.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    JsonNode type = block.get("type");
                    if (type == null) continue;
                    String typeStr = type.asText();
                    JsonNode name = block.get("name");
                    if (name == null || name.asText().isBlank()) continue;
                    String toolName = name.asText();
                    tools.add(toolName);

                    String input = "";
                    String output = "";
                    if ("tool_use".equals(typeStr)) {
                        JsonNode inputNode = block.get("input");
                        if (inputNode != null) {
                            input = Truncation.truncate(inputNode.toString(), INPUT_MAX_LEN);
                        }
                    } else if ("tool_result".equals(typeStr)) {
                        JsonNode outputNode = block.get("output");
                        if (outputNode != null) {
                            String raw = outputNode.toString();
                            int nl = raw.indexOf('\n');
                            output = nl > 0 ? raw.substring(0, nl) + " …" : Truncation.truncate(raw, 160);
                        }
                    }
                    details.add(new ToolCallDetail(toolName, "L2", input, output));
                }
            } catch (IOException e) {
                log.debug("parseL2ToolCalls: skipping malformed line: {}", e.getMessage());
            }
        }
        return new ParseL2Result(tools, details);
    }
}
