-- =====================================================================
--  005: API Key 命名空间权限隔离（可重复执行）
--  目的：支持签发受限角色的 Key（如 AGENT 只读问答，且只能看到指定的 1 个或多个 namespace）
-- =====================================================================

SET @db := DATABASE();

SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_api_key' AND COLUMN_NAME = 'allowed_namespaces'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_api_key ADD COLUMN allowed_namespaces VARCHAR(512) NULL COMMENT ''允许访问的命名空间（逗号分隔）；NULL或空表示全部'' AFTER role',
    'SELECT ''kb_api_key.allowed_namespaces already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
