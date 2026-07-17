package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * URL短链记录 — 存储原始URL与对应短码的映射关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlShortenerRecord {

    private Long id;

    private String shortCode;

    private String originalUrl;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}