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
        // 邮件/通知链接是普通浏览器导航，无法携带前端 localStorage 中的 X-User-Id。
        // 无登录身份也必须记录点击，人员字段保持为空，不得拦截到登录页。
        clicks.recordJob(null, executionId, null, loggedIn ? actor : "", null,
                "NOTIFICATION", returnFromLogin ? "LOGIN_VIEW" : "CLICK", loggedIn, ip, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/api/skill-jobs/executions/" + executionId + "/download")).build();
    }

    @GetMapping("/api/skill-flow-executions/notification-link")
    public ResponseEntity<Void> openFlow(@RequestParam("executionId") Long executionId,
            @RequestParam(name="returnFromLogin", defaultValue="false") boolean returnFromLogin,
            @RequestHeader(value="X-User-Id", required=false) String actor,
            @RequestHeader(value="User-Agent", required=false) String userAgent,
            @RequestHeader(value="X-Forwarded-For", required=false) String ip) {
        boolean loggedIn = actor != null && !actor.isBlank();
        // 匿名访问同样记录埋点，userId 为空；报告本身由公开的查看接口提供。
        clicks.record(null, executionId, null, loggedIn ? actor : "", null, "NOTIFICATION",
                returnFromLogin ? "LOGIN_VIEW" : "CLICK", loggedIn, ip, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/report/flows/" + executionId)).build();
    }
}
