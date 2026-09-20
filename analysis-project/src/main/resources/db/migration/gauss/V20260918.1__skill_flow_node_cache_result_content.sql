-- 节点缓存结果内容入库:结果全文直接存 result_content 列,取代 result_path 文件方案。
-- 清理(Janitor / cleanup)删除行时内容随之删除,不再有磁盘文件孤儿问题。
ALTER TABLE skill_flow_node_cache ADD COLUMN result_content TEXT;

COMMENT ON COLUMN skill_flow_node_cache.result_content IS '构建产物内容全文(入库存储);历史文件方案的行此列为 NULL,命中时按 INVALID 重建';
