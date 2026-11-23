-- =====================================================================
--  迁移 002：kb_document 增加 raw_content（保存解析后的 Markdown 原文）
--
--  用途：
--    1. 文档库可以"查看原文"，不用再从分块里拼；
--    2. 在线编辑/追加后重建索引（换行、标题结构都还在，切分结果才稳定）。
--
--  可重复执行：列已存在时什么都不做。
--  执行：mysql -h127.0.0.1 -P3307 -ultc -p ltc_rag < sql/migrations/002-raw-content.sql
-- =====================================================================

SET @col_exists := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'kb_document'
       AND COLUMN_NAME = 'raw_content'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN raw_content MEDIUMTEXT NULL COMMENT ''解析后的 Markdown 原文，供前端查看与在线编辑重建'' AFTER error',
    'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT IF(@col_exists = 0, '已添加 raw_content 列', 'raw_content 已存在，跳过') AS result;
