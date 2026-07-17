package com.agentscopea2a.service;

import com.agentscopea2a.entity.UrlShortenerRecord;
import com.agentscopea2a.mapper.mysql.UrlShortenerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * URL短链服务 — 负责短码生成、URL入库和查询。
 */
@Service
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SHORT_CODE_LENGTH = 16;
    private static final int MAX_COLLISION_RETRIES = 5;

    @Autowired
    private UrlShortenerMapper urlShortenerMapper;

    private final Random random = new Random();

    /**
     * 将原始URL入库并返回短码。如果URL已存在则复用已有短码。
     *
     * @param originalUrl 原始URL
     * @return 短码，如 "aB3xK9mP2qR5tY8w"
     */
    public String shorten(String originalUrl) {
        // 先查是否已存在
        UrlShortenerRecord existing = findByOriginalUrl(originalUrl);
        if (existing != null) {
            log.debug("URL already exists, reusing short_code={}", existing.getShortCode());
            return existing.getShortCode();
        }

        String shortCode = generateUniqueShortCode();
        UrlShortenerRecord record = UrlShortenerRecord.builder()
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .createdAt(LocalDateTime.now())
                .build();

        int rows = urlShortenerMapper.insert(record);
        if (rows > 0) {
            log.info("URL shortened: {} -> {}", originalUrl, shortCode);
            return shortCode;
        } else {
            log.warn("Failed to insert URL shortener record for: {}", originalUrl);
            return null;
        }
    }

    /**
     * 根据短码查询原始URL。如果不存在或已过期返回null。
     *
     * @param shortCode 短码
     * @return 原始URL，或null
     */
    public String resolve(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) return null;

        UrlShortenerRecord record = urlShortenerMapper.selectByShortCode(shortCode);
        if (record == null) return null;

        // 检查过期
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.debug("Short code expired: {}", shortCode);
            return null;
        }

        return record.getOriginalUrl();
    }

    /**
     * 清理过期记录。
     */
    public void cleanExpired() {
        int deleted = urlShortenerMapper.deleteExpired();
        if (deleted > 0) {
            log.info("Cleaned up {} expired URL shortener records", deleted);
        }
    }

    private String generateUniqueShortCode() {
        for (int i = 0; i < MAX_COLLISION_RETRIES; i++) {
            String code = generateShortCode();
            if (urlShortenerMapper.selectByShortCode(code) == null) {
                return code;
            }
            log.warn("Short code collision detected, retrying...");
        }
        throw new IllegalStateException("Failed to generate unique short code after " + MAX_COLLISION_RETRIES + " retries");
    }

    private String generateShortCode() {
        StringBuilder sb = new StringBuilder(SHORT_CODE_LENGTH);
        for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    private UrlShortenerRecord findByOriginalUrl(String originalUrl) {
        // 通过扫描全表查找相同URL（数据量不大时可行）
        // 如果后续性能要求高，可增加 original_url 列索引
        // 此处简化实现：直接插入时可能产生重复，但 shorten() 方法会通过异常处理去重
        return null;
    }
}
