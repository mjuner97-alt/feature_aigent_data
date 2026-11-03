package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.NotificationLinkClickFilterRequest;
import com.agentscopea2a.v2.skillManager.service.NotificationLinkClickService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

/** Unified access-audit endpoint used by the conversation-history tab. */
@RestController
@RequestMapping("/api/notification-access")
public class NotificationAccessController {
    private final NotificationLinkClickService service;
    public NotificationAccessController(NotificationLinkClickService service) { this.service = service; }
    @GetMapping("/environment")
    public java.util.Map<String, String> environment() { return java.util.Map.of("environment", service.activeEnvironment()); }
    @GetMapping("/link-clicks")
    public java.util.Map<String, Object> list(
            @RequestHeader("X-User-Id") String userId,
            NotificationLinkClickFilterRequest filter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.pageSummary(userId, filter, page, pageSize);
    }
    @GetMapping(value = "/link-clicks/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestHeader("X-User-Id") String userId,
                                         NotificationLinkClickFilterRequest filter) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=notification-access.csv")
                .body(service.exportCsv(userId, filter));
    }
}
