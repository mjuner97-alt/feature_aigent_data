package com.agentscopea2a.v2.auth.controller;

import com.agentscopea2a.constant.AgentToolConstant;
import com.agentscopea2a.v2.auth.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Receives the frontend's third-party user ID under the existing trusted-intranet identity model. */
@RestController
@RequestMapping("/api/auth/third-party")
public class ThirdPartySsoController {
    private final AuthService authService;
    private final boolean enabled;

    public ThirdPartySsoController(AuthService authService,
            @Value("${app.auth.third-party.enabled:true}") boolean enabled) {
        this.authService = authService;
        this.enabled = enabled;
    }

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(Map.of("enabled", enabled, "loginUrl", AgentToolConstant.THIRD_PARTY_LOGIN_URL));
    }

    public record LoginRequest(String userId) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (!enabled) return ResponseEntity.status(403).body(Map.of("message", "第三方登录未启用"));
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(authService.thirdPartyLogin(request.userId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
