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

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.service.ChatStreamService;
import com.agentscopea2a.service.impl.ChatStreamServiceImpl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Direct REST endpoint mirroring the A2A runner's supervisor wiring.
 *
 * <p>This controller is intentionally thin — it does no normalization, supervisor wiring, or
 * stream handling itself; all of that lives in
 * {@link ChatStreamServiceImpl}. The controller's only
 * job is to bind the HTTP shape ({@code POST /chatA2A}, {@code text/event-stream}) and hand the
 * request off.
 */
@RestController
public class ChatController {

    private final ChatStreamService chatStreamService;

    public ChatController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    @PostMapping(value = "/chatA2A", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        return chatStreamService.stream(req);
    }
}
