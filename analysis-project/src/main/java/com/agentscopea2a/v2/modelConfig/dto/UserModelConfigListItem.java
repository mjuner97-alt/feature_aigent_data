package com.agentscopea2a.v2.modelConfig.dto;

import com.agentscopea2a.entity.UserModelConfig;

import java.time.LocalDateTime;

/** 模型配置列表项。用户名来自人员信息表，缺失时由前端回退显示 userId。 */
public record UserModelConfigListItem(
        String userId,
        String userName,
        String provider,
        String token,
        String modelName,
        String requestUrl,
        LocalDateTime expireAt,
        LocalDateTime lastNotifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserModelConfigListItem of(UserModelConfig config, String userName) {
        return new UserModelConfigListItem(
                config.getUserId(), userName, config.getProvider(), config.getToken(),
                config.getModelName(), config.getRequestUrl(), config.getExpireAt(),
                config.getLastNotifiedAt(), config.getCreatedAt(), config.getUpdatedAt());
    }
}
