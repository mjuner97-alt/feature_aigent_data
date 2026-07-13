package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户模型配置 — 每个用户可绑定自己的 API Key / 模型 / 请求地址。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModelConfig {
    private Long userId;
    private String provider;
    private String token;
    private String modelName;
    private String requestUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
