/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.controller;

import com.agentscopea2a.v2.auth.dto.LoginRequest;
import com.agentscopea2a.v2.auth.dto.LoginResponse;
import com.agentscopea2a.v2.auth.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import com.agentscopea2a.v2.auth.dto.PasswordResetRequest;
import com.agentscopea2a.v2.auth.dto.PasswordResetVerifyRequest;
import com.agentscopea2a.v2.auth.dto.PasswordResetCompleteRequest;
import org.springframework.beans.factory.annotation.Value;

/**
 * 认证 REST 接口 - 轻量登录(工号校验)。
 * 前端登录后,后续请求通过 X-User-Id 请求头携带 userId。
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {
    public static final String SESSION_USER = "com.agentscopea2a.auth.userId";

    static LoginResponse establishSession(HttpServletRequest request, LoginResponse response) {
        request.getSession().setAttribute(SESSION_USER, response.getUserId());
        request.changeSessionId();
        return response;
    }

    private final AuthService authService;
    @Value("${app.auth.migration-secret:}") private String migrationSecret;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/migration/export")
    public ResponseEntity<?> export(@RequestHeader(value = "X-Auth-Migration-Secret", required = false) String secret) {
        if (migrationSecret == null || migrationSecret.isBlank() || !migrationSecret.equals(secret))
            return ResponseEntity.status(403).body(Map.of("message", "migration secret required"));
        return ResponseEntity.ok(Map.of("credentials", authService.exportCredentials()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        try {
            LoginResponse response = authService.login(request);
            return ResponseEntity.ok(establishSession(servletRequest, response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    public ResponseEntity<?> currentSession(@SessionAttribute(name = SESSION_USER, required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).header("Cache-Control", "no-store").build();
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("userId", userId));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<?> requestReset(@RequestBody PasswordResetRequest request) {
        return ResponseEntity.accepted().body(authService.requestPasswordReset(request.getUserId()));
    }

    @PostMapping("/password-reset/verify")
    public ResponseEntity<?> verifyReset(@RequestBody PasswordResetVerifyRequest request) {
        try { return ResponseEntity.ok(authService.verifyPasswordReset(request.getUserId(), request.getCode())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }

    @PostMapping("/password-reset/complete")
    public ResponseEntity<?> completeReset(@RequestBody PasswordResetCompleteRequest request) {
        try { authService.completePasswordReset(request.getUserId(), request.getResetSession(), request.getNewPassword()); return ResponseEntity.ok(Map.of("message", "密码已重置")); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }
}
