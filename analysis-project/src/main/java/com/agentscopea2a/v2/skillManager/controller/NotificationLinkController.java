package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.service.NotificationLinkClickService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
public class NotificationLinkController {
    private final NotificationLinkClickService clicks;
    public NotificationLinkController(NotificationLinkClickService clicks) { this.clicks = clicks; }
    @GetMapping("/api/skill-jobs/notification-link")
    public ResponseEntity<Void> open(@RequestParam("executionId") Long executionId,
            @RequestParam(name="returnFromLogin", defaultValue="false") boolean returnFromLogin,
            @RequestParam(name="accessToken", required=false) String accessToken,
            @RequestHeader(value="X-User-Id", required=false) String actor,
            @RequestHeader(value="User-Agent", required=false) String userAgent,
            @RequestHeader(value="X-Forwarded-For", required=false) String ip) {
        boolean loggedIn = actor != null && !actor.isBlank();
        if (!loggedIn) {
            String token = clicks.recordPendingJob(executionId, ip, userAgent);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/login?returnExecutionId=" + executionId + "&accessToken=" + token)).build();
        }
        if (returnFromLogin) clicks.completePendingJob(accessToken, actor);
        else clicks.recordJob(null, executionId, null, actor, null, "NOTIFICATION", "CLICK", true, ip, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/api/skill-jobs/executions/" + executionId + "/download")).build();
    }

    @GetMapping("/api/skill-flow-executions/notification-link")
    public ResponseEntity<Void> openFlow(@RequestParam("executionId") Long executionId,
            @RequestParam(name="returnFromLogin", defaultValue="false") boolean returnFromLogin,
            @RequestHeader(value="X-User-Id", required=false) String actor,
            @RequestHeader(value="User-Agent", required=false) String userAgent,
            @RequestHeader(value="X-Forwarded-For", required=false) String ip) {
        boolean loggedIn = actor != null && !actor.isBlank();
        if (!loggedIn) return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/login?returnFlowExecutionId=" + executionId)).build();
        clicks.record(null, executionId, null, actor, null, "NOTIFICATION", returnFromLogin ? "LOGIN_VIEW" : "CLICK", true, ip, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/report/flows/" + executionId)).build();
    }
}
