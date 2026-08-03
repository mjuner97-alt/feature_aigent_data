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
package com.agentscopea2a.v2.trace.writer;

import com.agentscopea2a.v2.trace.config.TraceProperties;
import com.agentscopea2a.v2.trace.model.AssembledTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** 降级写入器，ClickHouse 不可用时将 trace 以 JSONL 写入本地文件 */
@Component
public class TraceFallbackWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceFallbackWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TraceProperties properties;

    public TraceFallbackWriter(TraceProperties properties) {
        this.properties = properties;
    }

    public void write(AssembledTrace trace) {
        Path dir = Paths.get(properties.getFallback().getDir());
        String fileName = trace.conversation().getConversationId()
                + "_" + trace.conversation().getTraceId() + ".jsonl";
        Path file = dir.resolve(fileName);

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("TraceFallbackWriter create dir failed: {}: {}", dir, e.getMessage(), e);
            return;
        }
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(MAPPER.writeValueAsString(trace.conversation()));
            w.newLine();
            for (String ev : trace.eventJsons()) {
                w.write(ev);
                w.newLine();
            }
            log.info("TraceFallbackWriter wrote {} to {}",
                    trace.conversation().getConversationId(), file);
        } catch (IOException e) {
            log.error("TraceFallbackWriter write failed for conversationId={}: {}",
                    trace.conversation().getConversationId(), e.getMessage(), e);
        }
    }
}
