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
    private String userId;
    private String provider;
    private String token;
    private String modelName;
    private String requestUrl;
    /** 密钥到期时间，空表示无已知到期（仅靠探针探测）。 */
    private LocalDateTime expireAt;
    /** 最近一次过期通知时间，用于去重。 */
    private LocalDateTime lastNotifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
