-- =====================================================================
--  003: Ingestion v2-Lite 迁移脚本（可重复执行）
--  1. kb_document.source_hash : 区分原件字节 SHA256 与规范化 Markdown SHA256
--  2. kb_chunk.content_hash   : 分块文本 SHA256，用于增量原位复用 Qdrant vector_id
--  3. kb_chunk.page_no        : PDF 分块起始页码（1-based），用于引用页码回溯
-- =====================================================================

SET @db := DATABASE();

SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_document' AND COLUMN_NAME = 'source_hash'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_document ADD COLUMN source_hash CHAR(64) NULL COMMENT ''原件字节 SHA256'' AFTER content_hash',
    'SELECT ''kb_document.source_hash already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'content_hash'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_chunk ADD COLUMN content_hash CHAR(64) NULL COMMENT ''分块文本 SHA256，用于增量复用 vector_id'' AFTER char_count',
    'SELECT ''kb_chunk.content_hash already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'page_no'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_chunk ADD COLUMN page_no INT NULL COMMENT ''PDF 起始页码（从 1 开始）'' AFTER content_hash',
    'SELECT ''kb_chunk.page_no already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
