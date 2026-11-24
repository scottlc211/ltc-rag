-- =====================================================================
--  004: 命名空间注册表（可重复执行）
--  目的：让命名空间可以"先建后用"——空的库也能出现在下拉框里，
--        上传时才有得选。此前 /v1/namespaces 只从 kb_document 聚合，
--        没文档的库根本不存在。
-- =====================================================================

CREATE TABLE IF NOT EXISTS kb_namespace (
    name        VARCHAR(64)  NOT NULL COMMENT '命名空间标识（统一小写，字母/数字/-/_/.）',
    description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '说明，便于区分业务域',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库命名空间注册表';

-- 默认库先登记，保证首次部署下拉框里就有它
INSERT IGNORE INTO kb_namespace (name, description) VALUES ('ltc', '默认知识库');
