-- ============================================================================
-- P0-1 Flyway baseline: response_cache table
-- ----------------------------------------------------------------------------
-- Source: src/main/java/com/agentscopea2a/v2/cache/ResponseCacheService.java:263
--
-- Note: response-cache.enabled=false by default (deprecated HIT path, see
-- memory [[response_cache_deprecated]]), but the table is still created so
-- enabling it does not require manual DDL.
-- ============================================================================

CREATE TABLE IF NOT EXISTS response_cache (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  cache_key   VARCHAR(512)  NOT NULL,
  question    VARCHAR(1024) NOT NULL,
  response    MEDIUMTEXT    NOT NULL,
  created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  expire_at   TIMESTAMP     NOT NULL,
  UNIQUE KEY uk_cache_key (cache_key),
  INDEX idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
