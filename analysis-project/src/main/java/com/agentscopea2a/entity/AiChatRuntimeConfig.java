package com.agentscopea2a.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** One dictionary entry for a legacy {@code /ai/chat} runtime setting. */
@Data
public class AiChatRuntimeConfig {
    private String configKey;
    private String configValue;
    private String configDescription;
    private LocalDateTime updatedAt;
}
