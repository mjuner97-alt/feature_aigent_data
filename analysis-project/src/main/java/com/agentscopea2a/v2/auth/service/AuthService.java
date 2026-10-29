/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.auth.service;

import com.agentscopea2a.v2.auth.dto.LoginRequest;
import com.agentscopea2a.v2.auth.dto.LoginResponse;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.auth.mapper.AuthUserCredentialMapper;
import com.agentscopea2a.v2.auth.entity.AuthUserCredential;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量登录服务 - 校验 user_id 在 developer_pl_person_info 表中存在,
 * 返回姓名/部门/统计组/产品线信息。无密码、无 Session、无 Spring Security。
 */
@Service
public class AuthService {

    private final DeveloperPlPersonInfoMapper personInfoMapper;
    private final AdminRoleService adminRoleService;
    private final NotificationSender notificationSender;
    private final AiChatRuntimeConfigMapper runtimeConfigMapper;
    private final AuthUserCredentialMapper credentialMapper;
    /**
     * Lightweight fallback for isolated/test instances that do not have the
     * credential mapper wired. Production instances use the database mapper;
     * this map only keeps password operations internally consistent when the
     * service is constructed without Spring persistence dependencies.
     */
    private final Map<String, AuthUserCredential> transientCredentials = new ConcurrentHashMap<>();
    private final Map<String, ResetCode> resetCodes = new ConcurrentHashMap<>();
    private final Map<String, String> resetSessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final long CODE_TTL_SECONDS = 600;
    private static final long LOCK_SECONDS = 900;

    public AuthService(DeveloperPlPersonInfoMapper personInfoMapper, AdminRoleService adminRoleService) {
        this(personInfoMapper, adminRoleService, null, null, null);
    }

    public AuthService(DeveloperPlPersonInfoMapper personInfoMapper, AdminRoleService adminRoleService,
                       NotificationSender notificationSender) {
        this(personInfoMapper, adminRoleService, notificationSender, null, null);
    }

    public AuthService(DeveloperPlPersonInfoMapper personInfoMapper, AdminRoleService adminRoleService,
                       NotificationSender notificationSender, AiChatRuntimeConfigMapper runtimeConfigMapper) {
        this(personInfoMapper, adminRoleService, notificationSender, runtimeConfigMapper, null);
    }

    @Autowired
    public AuthService(DeveloperPlPersonInfoMapper personInfoMapper, AdminRoleService adminRoleService,
                       NotificationSender notificationSender, AiChatRuntimeConfigMapper runtimeConfigMapper,
                       AuthUserCredentialMapper credentialMapper) {
        this.personInfoMapper = personInfoMapper;
        this.adminRoleService = adminRoleService;
        this.notificationSender = notificationSender;
        this.runtimeConfigMapper = runtimeConfigMapper;
        this.credentialMapper = credentialMapper;
    }

    public LoginResponse login(LoginRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工号不能为空");
        }

        if (adminRoleService.isAdminUserId(userId)) {
            return adminLogin(userId, request.getPassword());
        }

        if (!passwordAuthEnabled()) {
            return normalUserLogin(userId);
        }
        Credential credential = loadCredential(userId);
        if (credential != null) {
            if (credential.lockedUntil > Instant.now().getEpochSecond()) throw new IllegalArgumentException("账号或密码错误");
            if (!matches(request.getPassword(), credential.hash)) {
                credential.failures++;
                if (credential.failures >= MAX_ATTEMPTS) credential.lockedUntil = Instant.now().getEpochSecond() + LOCK_SECONDS;
                saveLoginState(userId, credential);
                throw new IllegalArgumentException("账号或密码错误");
            }
            credential.failures = 0;
            saveLoginState(userId, credential);
        }

        int count = personInfoMapper.countByUserId(userId);
        if (count == 0) {
            throw new IllegalArgumentException("账号不存在,请检查输入");
        }

        if (credential == null) {
            throw new IllegalArgumentException("该账号尚未设置密码，请先通过忘记密码设置密码");
        }

        return normalUserLogin(userId);
    }

    private LoginResponse normalUserLogin(String userId) {
        List<DeveloperPlPersonInfo> records = personInfoMapper.selectByUserId(userId);
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("账号不存在,请检查输入");
        }
        DeveloperPlPersonInfo first = records.get(0);

        List<String> departments = records.stream()
                .map(DeveloperPlPersonInfo::getDepartment)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> statisticsGroups = records.stream()
                .map(DeveloperPlPersonInfo::getStatisticsGroup)
                .filter(g -> g != null && !g.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> productLines = records.stream()
                .map(DeveloperPlPersonInfo::getProductLine)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .collect(Collectors.toList());

        return new LoginResponse(
                userId,
                first.getName(),
                departments,
                statisticsGroups,
                productLines,
                "登录成功",
                false,
                adminRoleService.isProductionMode()
        );
    }

    private boolean passwordAuthEnabled() {
        if (runtimeConfigMapper == null) return true;
        try {
            AiChatRuntimeConfig config = runtimeConfigMapper.selectByConfigKey(AiChatRuntimeConfigKeys.PASSWORD_AUTH_ENABLED);
            return config == null || config.getConfigValue() == null
                    || !"false".equalsIgnoreCase(config.getConfigValue().trim());
        } catch (Exception ignored) {
            return true;
        }
    }

    public void setPassword(String userId, String password) {
        String hashed = hash(password);
        if (credentialMapper != null) {
            credentialMapper.upsertPassword(userId, hashed);
            return;
        }
        AuthUserCredential row = new AuthUserCredential();
        row.setUserId(userId);
        row.setPasswordHash(hashed);
        row.setLoginFailures(0);
        transientCredentials.put(userId, row);
    }

    public List<Map<String, Object>> exportCredentials() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (credentialMapper != null) {
            // Export is intended only for controlled migration; hashes never leave as plaintext.
            for (String userId : personInfoMapper.selectAllUserIds()) {
                AuthUserCredential row = credentialMapper.selectByUserId(userId);
                if (row != null) out.add(Map.of("userId", row.getUserId(), "passwordHash", row.getPasswordHash()));
            }
        }
        return out;
    }

    public Map<String, Object> requestPasswordReset(String userId) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        resetCodes.put(userId, new ResetCode(hash(code), Instant.now().getEpochSecond() + CODE_TTL_SECONDS));
        if (notificationSender != null && userId != null && !userId.isBlank()) {
            notificationSender.sendPasswordReset(userId, code);
        }
        return Map.of("accepted", true);
    }

    public Map<String, String> verifyPasswordReset(String userId, String code) {
        ResetCode reset = resetCodes.get(userId);
        if (reset == null || reset.used || reset.expiresAt < Instant.now().getEpochSecond() || reset.attempts >= MAX_ATTEMPTS || !matches(code, reset.hash)) {
            if (reset != null && !reset.used && !matches(code, reset.hash)) reset.attempts++;
            throw new IllegalArgumentException("验证码无效或已过期");
        }
        String session = UUID.randomUUID().toString();
        resetSessions.put(session, userId);
        return Map.of("resetSession", session, "expiresIn", "600");
    }

    public void completePasswordReset(String userId, String session, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || !userId.equals(resetSessions.remove(session))) throw new IllegalArgumentException("重置请求无效");
        ResetCode reset = resetCodes.get(userId);
        if (reset == null || reset.used || reset.expiresAt < Instant.now().getEpochSecond()) throw new IllegalArgumentException("重置请求无效");
        reset.used = true;
        setPassword(userId, newPassword);
    }

    private Credential loadCredential(String userId) {
        AuthUserCredential row = credentialMapper != null
                ? credentialMapper.selectByUserId(userId)
                : transientCredentials.get(userId);
        if (row == null) return null;
        Credential c = new Credential(row.getPasswordHash());
        c.failures = row.getLoginFailures() == null ? 0 : row.getLoginFailures();
        c.lockedUntil = row.getLockedUntil() == null ? 0 : row.getLockedUntil().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        return c;
    }

    private void saveLoginState(String userId, Credential credential) {
        java.time.LocalDateTime locked = credential.lockedUntil <= 0 ? null : java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(credential.lockedUntil), java.time.ZoneId.systemDefault());
        if (credentialMapper != null) {
            credentialMapper.updateLoginState(userId, credential.failures, locked);
            return;
        }
        AuthUserCredential row = transientCredentials.get(userId);
        if (row != null) {
            row.setLoginFailures(credential.failures);
            row.setLockedUntil(locked);
        }
    }

    private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static boolean matches(String raw, String expected) { return MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)); }
    private static final class Credential { final String hash; int failures; long lockedUntil; Credential(String hash){this.hash=hash;} }
    private static final class ResetCode { final String hash; final long expiresAt; int attempts; boolean used; ResetCode(String hash,long expiresAt){this.hash=hash;this.expiresAt=expiresAt;} }

    private LoginResponse adminLogin(String userId, String password) {
        if (!adminRoleService.matchesPassword(userId, password)) {
            throw new IllegalArgumentException("管理员密码错误");
        }
        return new LoginResponse(userId, userId, List.of(), List.of(), List.of(), "登录成功",
                true, adminRoleService.isProductionMode());
    }
}
