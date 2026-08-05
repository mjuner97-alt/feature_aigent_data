-- ============================================================================
-- url_shortener 表 GaussDB 基线迁移
-- ----------------------------------------------------------------------------
-- 背景: 之前 DDL 只放在 db/migration/mysql/ (V20260718.3__baseline_url_shortener.sql),
-- 但 UrlShortenerMapper 走 gauss 数据源 (mapper/gauss/UrlShortenerMapper.xml),
-- 导致 GaussDB 上 url_shortener 表不存在, CsvDownloadTool.generate_csv_download_url
-- 调用时报 "relation url_shortener does not exist".
--
-- commit 9869224 (短连接下载从mysql移动到gauss) 已将 mapper 切到 gauss,
-- 本脚本补齐 GaussDB 端建表 DDL.
-- 目标数据库: openGauss (PostgreSQL 兼容)
-- ============================================================================

CREATE TABLE IF NOT EXISTS url_shortener (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL DEFAULT NULL,
    CONSTRAINT uk_url_shortener_short_code UNIQUE (short_code)
);

CREATE INDEX IF NOT EXISTS idx_url_shortener_expires_at
    ON url_shortener (expires_at);
