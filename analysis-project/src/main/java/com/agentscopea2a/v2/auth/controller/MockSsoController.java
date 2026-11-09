package com.agentscopea2a.v2.auth.controller;

import com.agentscopea2a.v2.auth.service.AuthService;
import com.agentscopea2a.v2.auth.service.AdminRoleService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Clock;
import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/** Local-only provider simulation. All credentials are scoped to the initiating HTTP session. */
@RestController
@RequestMapping("/api/auth/mock-sso")
public class MockSsoController {
    private static final String PENDING = MockSsoController.class.getName();
    private static final long TTL_SECONDS = 120;
    private final AuthService authService;
    private final boolean enabled;
    private final String userId;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public MockSsoController(AuthService authService, AdminRoleService roles, Environment environment,
            @Value("${app.auth.mock-sso.enabled:false}") boolean enabled,
            @Value("${app.auth.mock-sso.user-id:skill_demo_u001}") String userId) {
        this(authService, enabled && !roles.isProductionMode()
                && !environment.acceptsProfiles(Profiles.of("prod", "production")), userId, Clock.systemUTC());
    }

    MockSsoController(AuthService authService, boolean enabled, String userId, Clock clock) {
        this.authService = authService;
        this.enabled = enabled;
        this.userId = userId;
        this.clock = clock;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", enabled, "authorizeUrl", "/api/auth/mock-sso/authorize", "expiresIn", TTL_SECONDS);
    }

    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestParam String state, HttpSession session) {
        if (!enabled) return disabled();
        if (state == null || !state.matches("[a-zA-Z0-9_-]{32,128}")) return invalid();
        String request = UUID.randomUUID().toString();
        synchronized (session) {
            session.setAttribute(PENDING, new Pending(state, request, null, clock.millis() + TTL_SECONDS * 1000));
        }
        return redirect("/api/auth/mock-sso/provider?request=" + request);
    }

    @GetMapping("/provider")
    public ResponseEntity<?> provider(@RequestParam String request,
            @RequestParam(defaultValue = "false") boolean fail, HttpSession session) {
        if (!enabled) return disabled();
        synchronized (session) {
            Pending pending = (Pending) session.getAttribute(PENDING);
            if (pending == null || pending.expiresAt <= clock.millis() || pending.ticket != null
                    || !pending.request.equals(request)) return invalid();
            if (fail) {
                session.removeAttribute(PENDING);
                return redirect("/login?ssoError=access_denied&state=" + pending.state);
            }
            String ticket = UUID.randomUUID().toString();
            session.setAttribute(PENDING, new Pending(pending.state, pending.request, ticket,
                    clock.millis() + TTL_SECONDS * 1000));
            return redirect("/login?ssoTicket=" + ticket + "&state=" + pending.state);
        }
    }

    public record ExchangeRequest(String ticket, String state) {}

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest request, HttpSession session) {
        if (!enabled) return disabled();
        synchronized (session) {
            Pending pending = (Pending) session.getAttribute(PENDING);
            if (pending == null || pending.expiresAt <= clock.millis() || pending.ticket == null
                    || !pending.ticket.equals(request.ticket()) || !pending.state.equals(request.state())) return invalid();
            session.removeAttribute(PENDING); // Consume atomically, including when the personnel lookup fails.
        }
        try {
            return ResponseEntity.ok(authService.mockSsoLogin(userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private ResponseEntity<?> disabled() { return ResponseEntity.status(403).body(Map.of("message", "模拟认证未启用")); }
    private ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message", "认证状态或凭证无效、已过期，请重试")); }
    private ResponseEntity<?> redirect(String location) {
        return ResponseEntity.status(302).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer").location(URI.create(location)).build();
    }
    private record Pending(String state, String request, String ticket, long expiresAt) implements Serializable {}
}
