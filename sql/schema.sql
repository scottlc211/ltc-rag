-- =====================================================================
--  LTC 知识库 DDL（MySQL 8.0，utf8mb4）
--  手动执行：mysql -uroot -p ltc_rag < sql/schema.sql
--  Docker 首次启动时由 /docker-entrypoint-initdb.d 自动执行
--  注意：中文全文检索依赖 ngram 分词器（MySQL 内置，默认 ngram_token_size=2）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 文档：doc_key 是调用方给的稳定标识（如 "ltc/design/api-spec"），
-- 重复上传同一个 doc_key 走"版本更新"而不是"再插一条"。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    doc_key       VARCHAR(191) NOT NULL COMMENT '业务侧稳定标识，如 ltc/design/api-spec',
    namespace     VARCHAR(64)  NOT NULL COMMENT '知识库/版本隔离，如 ltc、ltc-v2.3',
    title         VARCHAR(255) NOT NULL,
    doc_format    VARCHAR(16)  NOT NULL COMMENT 'md/docx/xlsx/pdf/html/txt',
    origin_url    VARCHAR(1024)         DEFAULT NULL COMMENT '原文链接，用于引用回链',
    content_hash  CHAR(64)     NOT NULL COMMENT '规范化 Markdown 原文 SHA256，判断是否需要重建',
    source_hash   CHAR(64)              DEFAULT NULL COMMENT '原件字节 SHA256（上传文件与在线编辑解耦）',
    version       INT          NOT NULL DEFAULT 1,
    status        VARCHAR(16)  NOT NULL COMMENT 'READY / FAILED',
    chunk_count   INT          NOT NULL DEFAULT 0,
    char_count    INT          NOT NULL DEFAULT 0,
    error         VARCHAR(1000)         DEFAULT NULL,
    raw_content   MEDIUMTEXT            DEFAULT NULL COMMENT '解析后的 Markdown 原文，供前端查看与在线编辑重建',
    metadata      JSON                  DEFAULT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_key (namespace, doc_key),
    KEY idx_ns (namespace, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库文档';

-- ---------------------------------------------------------------------
-- 分块：PARENT 只做上下文回填（不向量化），CHILD 入口向量化。
-- 命中 CHILD 后回填 PARENT 全文，解决"512 token 切碎了上下文"的问题。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id       BIGINT       NOT NULL,
    chunk_type   VARCHAR(8)   NOT NULL COMMENT 'PARENT / CHILD',
    chunk_index  INT          NOT NULL COMMENT '同一文档内的顺序号，便于还原原文位置',
    parent_id    BIGINT                DEFAULT NULL COMMENT 'CHILD 指向所属 PARENT',
    heading_path VARCHAR(512)          DEFAULT NULL COMMENT '标题路径，如 接口规范 > 订单 > 创建订单',
    content      MEDIUMTEXT   NOT NULL,
    char_count   INT          NOT NULL,
    content_hash CHAR(64)              DEFAULT NULL COMMENT '分块文本 SHA256，用于增量原位复用 Qdrant vector_id',
    page_no      INT                   DEFAULT NULL COMMENT 'PDF 起始页码（1-based），非 PDF 为 NULL',
    vector_id    CHAR(36)              DEFAULT NULL COMMENT 'Qdrant point id（仅叶子块：CHILD/CODE/TABLE）',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_doc (doc_id, chunk_index),
    KEY idx_parent (parent_id),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库分块';

-- ---------------------------------------------------------------------
-- API Key：只存 SHA256，明文仅在生成时返回一次。
-- role: ADMIN（可入库/删除）、AGENT（只可问答）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_api_key (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64) NOT NULL,
    key_prefix   VARCHAR(16) NOT NULL COMMENT '明文前 12 位，便于人工识别',
    key_hash     CHAR(64)    NOT NULL,
    role         VARCHAR(16) NOT NULL DEFAULT 'AGENT',
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    quota_per_day INT                 DEFAULT NULL COMMENT '每日调用上限，null 表示不限',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_used_at DATETIME(3)          DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_hash (key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '调用方密钥';

-- ---------------------------------------------------------------------
-- 异步问答任务：轮询式 worker（FOR UPDATE SKIP LOCKED），不依赖 MQ，
-- 重启不丢任务，天然支持多实例。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    task_id     CHAR(32)     NOT NULL COMMENT '对外暴露的任务号',
    api_key_id  BIGINT                DEFAULT NULL,
    namespace   VARCHAR(64)           DEFAULT NULL,
    question    TEXT         NOT NULL,
    options     JSON                  DEFAULT NULL,
    callback_url VARCHAR(1024)        DEFAULT NULL,
    callback_secret VARCHAR(64)       DEFAULT NULL COMMENT '每个任务独立回调签名密钥，提交时返回给调用方',
    status      VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    answer      MEDIUMTEXT            DEFAULT NULL,
    citations   JSON                  DEFAULT NULL,
    error       VARCHAR(2000)         DEFAULT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at  DATETIME(3)           DEFAULT NULL,
    finished_at DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_status (status, id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '异步问答任务';

-- ---------------------------------------------------------------------
-- 回调发件箱：先落库再投递，保证"任务完成"与"回调送达"解耦。
-- 指数退避 + 最大次数 + 死信（FAILED 状态 + 手动重放接口）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_callback_outbox (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    task_id       CHAR(32)      NOT NULL,
    url           VARCHAR(1024) NOT NULL,
    payload       MEDIUMTEXT    NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    attempts      INT           NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_code     INT                    DEFAULT NULL,
    last_error    VARCHAR(1000)          DEFAULT NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_due (status, next_retry_at),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '回调发件箱';

-- ---------------------------------------------------------------------
-- 问答审计：谁、问了什么、检索到几条、耗时、token 消耗
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_qa_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    task_id         CHAR(32)             DEFAULT NULL,
    api_key_id      BIGINT               DEFAULT NULL,
    mode            VARCHAR(16) NOT NULL COMMENT 'sync/stream/async/openai',
    namespace       VARCHAR(64)          DEFAULT NULL,
    question        VARCHAR(2000) NOT NULL,
    answer_chars    INT                  DEFAULT NULL,
    hit_count       INT                  DEFAULT NULL,
    retrieval_ms    INT                  DEFAULT NULL,
    llm_ms          INT                  DEFAULT NULL,
    total_ms        INT                  DEFAULT NULL,
    prompt_tokens   INT                  DEFAULT NULL,
    completion_tokens INT                DEFAULT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_key (api_key_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '问答审计日志';

-- ---------------------------------------------------------------------
-- 运行期配置：支持在管理页面动态修改 CHAT_MODEL / CHAT_BASE_URL / CHAT_API_KEY，
-- 无需重启容器且重启后依然持久化保留。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_config (
    config_key   VARCHAR(64)   NOT NULL COMMENT '配置键，如 chat.base_url / chat.api_key / chat.model',
    config_value VARCHAR(2048) NOT NULL COMMENT '配置值',
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '运行期动态配置';

-- ---------------------------------------------------------------------
-- 命名空间注册表：让知识库可以"先建后用"。
-- 只从 kb_document 聚合的话，没传过文档的库在下拉框里根本不存在，
-- 也就没法"先选库再上传"。这里显式登记，文档数仍从 kb_document 实时统计。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_namespace (
    name        VARCHAR(64)  NOT NULL COMMENT '命名空间标识（统一小写，字母/数字/-/_/.）',
    description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '说明，便于区分业务域',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库命名空间注册表';

INSERT IGNORE INTO kb_namespace (name, description) VALUES ('ltc', '默认知识库');
