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
package com.agentscopea2a.v2.trace.controller;

import com.agentscopea2a.v2.trace.service.TraceQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Trace 查询 REST 接口，提供会话列表和单会话详情两个端点 */
@RestController
@RequestMapping("/api/trace")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TraceController {

    private final TraceQueryService traceQueryService;

    public TraceController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    @GetMapping("/conversations")
    public Map<String, Object> listConversations(
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        return traceQueryService.listConversations(source, userId, page, size);
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> getDetail(
            @PathVariable("conversationId") String conversationId) {
        Map<String, Object> detail = traceQueryService.getDetail(conversationId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "conversation not found: " + conversationId);
        }
        return ResponseEntity.ok(detail);
    }
}
