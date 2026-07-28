-- ============================================================================
-- P0-1 Flyway baseline: url_shortener table
-- ----------------------------------------------------------------------------
-- Source: src/main/java/com/agentscopea2a/entity/UrlShortenerRecord.java
--         src/main/resources/mybatis/mapper/mysql/UrlShortenerMapper.xml
--
-- Note: DDL was previously missing from codebase - table had to be created
-- manually via `docker exec mymysql mysql -e 'CREATE TABLE ...'`. This
-- baseline captures the schema derived from the entity + mapper XML so new
-- environments get it automatically.
-- ============================================================================

CREATE TABLE IF NOT EXISTS url_shortener (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  short_code VARCHAR(32) NOT NULL,
  original_url VARCHAR(2048) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NULL DEFAULT NULL,
  UNIQUE KEY uk_short_code (short_code),
  KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
