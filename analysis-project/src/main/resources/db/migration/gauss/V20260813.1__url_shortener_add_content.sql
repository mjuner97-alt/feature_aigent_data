-- ============================================================================
-- url_shortener 表加 content/filename/mime_type 列 (GaussDB)
-- ----------------------------------------------------------------------------
-- 背景: 现有 CsvDownloadTool 传磁盘 agentPath, chat 结束 buildCleanup 删 taskBucket
-- 后链接 404. 新方案支持"直接指定数据内容": 把 markdown 表/CSV 字符串直接落
-- url_shortener.content 列, 不依赖磁盘 artifact, 跨会话清理安全.
--
-- original_url 允许空: content 模式下不用 agentPath, original_url=NULL;
--                     老路径 (generate_csv_download_url) 继续用 original_url.
--
-- 内容上限: 业务侧 5MB, GaussDB TEXT 最大 ~1GB.
-- ============================================================================

ALTER TABLE url_shortener
  ADD COLUMN content    TEXT          NULL DEFAULT NULL,
  ADD COLUMN filename   VARCHAR(255)  NULL DEFAULT NULL,
  ADD COLUMN mime_type  VARCHAR(128)  NULL DEFAULT NULL;

ALTER TABLE url_shortener ALTER COLUMN original_url DROP NOT NULL;
