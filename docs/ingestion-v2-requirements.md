# 文档入库与切分增强（Ingestion v2-Lite）开发需求文档

| 项 | 内容 |
|---|---|
| 文档版本 | **v0.2（架构师瘦身实战版 · 待老大拍板）** |
| 编写日期 | 2026-11-23 |
| 生产环境基线 | RackNerd 3C4G（Ubuntu 24.04）+ 宿主机 MySQL 8.0 + Qdrant + 本地 CPU Embedding/OCR 容器（`ltc-rag-ocr-1`）+ Cloudflare/Nginx（`https://rag.932000.xyz`） |
| 关联代码 | `chunk/Chunker.java`、`chunk/ChunkPiece.java`、`parser/PdfParser.java`、`service/IngestionService.java`、`repo/KnowledgeRepository.java`、`api/DocumentController.java`、`static/index.html` |
| 核心原则 | **不重复造 WeKnora / RAGFlow 已有的“重型企业网盘轮子”，只做能将研发文档/PRD 检索准确率与稳定性提升一个数量级的“核心刀刃”** |

---

## 1. 战略定位与架构裁决（为什么做 v2-Lite）

### 1.1 生态位边界
市面上已有 **Tencent/WeKnora**、**RAGFlow**、**Dify** 等大厂团队维护的重型开源 RAG 平台（动辄依赖 Go/Python + Postgres + Redis + MinIO + Neo4j 6~7 个容器，起步 4C8G/16G）。

`ltc-rag` 的不可替代性在于：
1. **极致轻量**：在 3C4G 服务器上复用宿主机 MySQL，全套（含本地 CPU 向量化 + 本地 RapidOCR + Qdrant + Magpie 网关）运行后整机仍剩 **1.8GB+** 空闲内存；
2. **Java / Spring Boot 母语可控**：全库仅约 50 个 Java 类 + 单文件 SPA，针对自身研发文档（PRD、接口文档、数据库表结构字典）可秒级定制；
3. **Agent 优先**：结构化引用 `citations[]` + OpenAI 兼容协议 + 带 HMAC 签名与 SSRF 防护的异步回调。

### 1.2 需求断舍离（v0.1 → v0.2 裁剪清单）

| 原 v0.1 需求 | v0.2 裁决 | 裁决理由 |
|---|---|---|
| **R8 表格 / 代码块原子感知切分** | ✅ **保留并升为 P0（首位）** | 研发文档与 PRD 中包含大量数据库表结构（如 `t_daily`、`t_clue`）和配置代码。现有切分器会切断表格导致丢表头、腰斩代码块导致围栏错乱，改造成本仅 ~120 行代码，检索准确率收益极高。 |
| **R4 块哈希增量向量复用** | ✅ **保留并重构为“零膨胀方案”（P0）** | 废弃原方案在 MySQL 存 800MB `MEDIUMBLOB` 的 `kb_embedding_cache`（会撑爆 3C4G 宿主机的 256M InnoDB Buffer Pool）；改为**基于 `kb_chunk.content_hash` 直接原位保留未变分块的 Qdrant `vector_id`**，零额外存储开销！ |
| **R3 解析预检（Preview）+ R9 PDF 页码锚点** | ✅ **合并保留（P1）** | 预检门禁能提前发现“PDF 表格被拉平 / 缺标题 / 围栏未闭合”，页码锚点让 PDF 引用精确到 `第 N 页`，改动小、实用性强。 |
| **R2 异步入库与进度** | ✅ **简化为可选异步兜底（P1）** | 生产域名挂在 Cloudflare 下（有 100s 硬超时限制）。保持默认同步兼容现有调用方，新增 `?async=true`（前端对大文件自动启用）走后台线程池 + 进度轮询，防 Cloudflare `524` 超时。 |
| R1 原件对象存储 / R5 多版本回滚 UI | ❌ **砍掉（不做）** | 属于通用网盘/CMS 体力活，且在容器内落本地磁盘增加备份复杂度，现有 `raw_content` 在线编辑已够用。 |
| R6 文档级复杂 ACL（`JSON_OVERLAPS`） | ❌ **砍掉（不做）** | 现有 `namespace` 隔离 + `ADMIN/AGENT` 角色 + 每日配额已满足团队自用；真要多租户复杂 RBAC 时直接上 WeKnora。 |
| R7 跨文档去重扫描 / R11 远端源同步 | ❌ **砍掉（不做）** | 低频边缘需求，投入产出比低。 |

> **工期对比**：原 v0.1 需 **12 人日**（新增 4 张大表、13 个接口）；瘦身后的 **v0.2（v2-Lite）仅需 2.5 人日**（仅新增 1 张轻量任务表或内存队列 + 3 个核心字段），刀刀砍在痛点上。

---

## 2. 核心需求详细设计

### 需求 R1 · 表格与代码块原子感知切分（P0 · 1.0 人日）

#### 2.1 现状痛点
1. **表格被腰斩丢表头**：如《昆仑 LTC PRD》或图片 OCR 还原出的 30 行数据库表名对照表，超过 `maxChildChars=480` 字后从第 12 行切断，下半截分块里只有 `| t_daily | 日报 | ... |`，丢失了第一行 `| 英文表名 | 中文表名 | 模块 |` 表头，导致检索与 LLM 理解失准。
2. **代码块被腰斩破围栏**：`Chunker.readSections()` 目前只做到了“代码块里的 `#` 不被当成 Markdown 标题”，但 `splitLong()` 按段落/句子硬切时，会把一个 600 字的 ` ```sql ... ``` ` 代码块从中间劈成两半：前半块只有开头 ` ```sql ` 没有结尾，后半块只有结尾 ` ``` ` 没有开头，不仅向量语义破碎，前端 Markdown 渲染也会错乱。
3. **⚠️ 致命协同坑（必须同步修复）**：若将 `kb_chunk.chunk_type` 细分为 `CHILD / CODE / TABLE`，现有 [`KnowledgeRepository.fulltextSearch()`](../src/main/java/com/ltc/rag/repo/KnowledgeRepository.java#L233) 写死了 `WHERE c.chunk_type = 'CHILD'`，会导致代码块和表格在 BM25 全文检索中**彻底无法召回**！

#### 2.2 切分算法设计
在 [`Chunker.java`](../src/main/java/com/ltc/rag/chunk/Chunker.java) 中，将每个章节（`Section`）的正文先解析为三类**原子块（`AtomicBlock`）**：`TEXT`、`CODE`、`TABLE`。

```
Section Body
   ├─ 1. 扫描围栏代码块 (```lang ... ``` 或 ~~~ ... ~~~) ──▶ AtomicBlock(CODE, lang, content)
   ├─ 2. 扫描连续 Markdown 表格行 (首尾或含 | 的连续行 + 分隔行 |---|) ──▶ AtomicBlock(TABLE, headerLines, dataRows)
   └─ 3. 其余普通文本 ──▶ AtomicBlock(TEXT, content)
```

针对三类原子块分别执行切分策略（单个子块总字符预算 `budget = maxChildChars - headingCost`）：

| 原子块类型 | 切分与自愈策略 | `chunk_type` 落库值 |
| :--- | :--- | :--- |
| **`TABLE`（表格）** | • **未超预算**：整表作为一个块（若紧邻的前后短文本能一起塞进 `budget` 则合并，否则独立成块）。<br>• **超出预算（表头自动跟随 Header Propagation）**：提取前两行 `header = row[0] + "\n" + row[1]`（表头 + `| --- |` 分隔行），按数据行切分；**每个切出的子块开头都自动冠以 `header`**，确保任意子块都是合法、带表头的完整 Markdown 表格！<br>• 表格块**不叠加**普通文本的尾部 `overlap`，避免破坏表格语法。 | `TABLE` |
| **`CODE`（代码块）** | • **未超预算**：整段代码保留原围栏作为一个块。<br>• **超出预算（围栏自愈 Fence Healing）**：剥离首尾围栏行（记住开头的 `openFence` 如 ```` ```java ```` 和结尾的 ```` ``` ````），内部优先按空行 `\n\n` → 单行 `\n` 切分；**每个切出的子块自动包裹 `openFence + "\n" + part + "\n" + closeFence`**，确保每个块的围栏标记成对闭合。 | `CODE` |
| **`TEXT`（普通段落）** | • 沿用现有的“段落 `\n{2,}` → 句子 `SENTENCE` → 硬切 + 相邻 `TEXT` 块间补充 `overlapChars`”逻辑。 | `CHILD` |

#### 2.3 检索与入库协同改动
1. [`ChunkPiece.java`](../src/main/java/com/ltc/rag/chunk/ChunkPiece.java)：
   - 新增常量 `public static final String CODE = "CODE";` 与 `public static final String TABLE = "TABLE";`；
   - `isParent()` 保持 `PARENT.equals(type)`（即 `CHILD`、`CODE`、`TABLE` 均视为需要向量化的叶子块）。
2. [`KnowledgeRepository.java`](../src/main/java/com/ltc/rag/repo/KnowledgeRepository.java#L233)：
   - 将 `fulltextSearch()` 中的 `WHERE c.chunk_type = 'CHILD'` 修改为：
     ```sql
     WHERE c.chunk_type IN ('CHILD', 'CODE', 'TABLE')
     ```

#### 2.4 验收标准
- [ ] 30 行 Markdown 表格切分后产生多个 `TABLE` 块，每个块的前两行均为完整表头与 `| --- |` 分隔线。
- [ ] 800 字长代码块切分后产生多个 `CODE` 块，每个块均以 ```` ```lang ```` 开头、以 ```` ``` ```` 结尾，前端渲染无破损。
- [ ] 通过 BM25 全文检索表内字段名或代码内函数名，能正常召回 `TABLE` 和 `CODE` 块。

---

### 需求 R2 · 哈希语义对齐 + 原位增量向量复用（P0 · 0.5 人日）

#### 2.1 现状痛点
1. **哈希语义分裂**：
   - 上传文件（`IngestionService.doIngest`）把**原始文件字节（如 `.docx`/`.pdf` 二进制）**的 SHA256 存入了 `kb_document.content_hash`；
   - 在线编辑（`IngestionService.replaceContent`）把**Markdown 文本**的 SHA256 存入了同一个 `kb_document.content_hash`；
   - 导致上传后再在线编辑（或反之）时，哈希对比口径不一致。
2. **改一字重算全篇**：
   - 在线编辑一篇 200 块的长文档只改了一个错别字，或者调 `/v1/documents/{id}/append` 追加了 2 行 FAQ，现状会重新对全部 200 个块调 Embedding、删掉 Qdrant 里 200 个旧点再写 200 个新点。

#### 2.2 零膨胀增量复用设计（不在 MySQL 存 BLOB 向量）
1. **拆分文档级双哈希**：
   - `kb_document` 新增 `source_hash CHAR(64) NULL`（存原始上传文件字节的 SHA256；纯文本/在线编辑时等于 `content_hash`）；
   - `kb_document.content_hash` 统一定义为 **解析后规范化 Markdown 正文（`raw_content`）的 UTF-8 SHA256**。
2. **分块级 `content_hash` 原位复用 Qdrant `vector_id`**：
   - `kb_chunk` 新增 `content_hash CHAR(64) NULL`（对叶子块的 `content` 计算 SHA256）。
   - 当更新已有文档（`docId` 已存在且处于向量模式）时：
     1. 从 `kb_chunk` 查出该文档现有的 `Map<String, String> existingVectorByHash`（`content_hash -> vector_id`）；
     2. 遍历新切出的叶子块 `children`：
        - 若 `existingVectorByHash.containsKey(piece.contentHash())`：**直接复用该 `vector_id`**（从待删集合中移除该 `vector_id`，不调用 `EmbeddingClient`，也不重新 upsert Qdrant）；
        - 若未命中：收集到 `toEmbed` 列表，仅对这部分新增/修改的分块批量调用 `embeddings.embedAll()` 并生成新 `vectorId` 写入 Qdrant！
     3. 事务提交后，仅向 Qdrant 删除**未被复用的淘汰旧向量（`staleVectorIds - reusedVectorIds`）**。

```
旧文档分块: [Chunk A (hash1 -> vec1), Chunk B (hash2 -> vec2), Chunk C (hash3 -> vec3)]
用户修改了 Chunk B 为 B'，新切出: [Chunk A (hash1), Chunk B' (hash2_new), Chunk C (hash3)]
   ├─ Chunk A: 命中 hash1 ──▶ 直接复用 vec1（0 向量计算，0 Qdrant 写入）
   ├─ Chunk B': 未命中    ──▶ 仅对 B' 计算 Embedding ──▶ 写入 Qdrant (vec4)
   ├─ Chunk C: 命中 hash3 ──▶ 直接复用 vec3（0 向量计算，0 Qdrant 写入）
   └─ 事务换块后清理 Qdrant: 仅删除淘汰的 [vec2]！
```

#### 2.3 收益与优势
- **MySQL 零空间膨胀**：不需要建 800MB 的 `kb_embedding_cache` BLOB 表，不挤占 3C4G 服务器的 InnoDB 缓冲池。
- **追加与微调秒级完成**：`/v1/documents/{id}/append` 追加一段话时，前面 99% 的分块全部原位复用，只算最后 1~2 个新块，耗时从十几秒降至 **< 200ms**！

---

### 需求 R3 · 解析质量预检（Preview）与 PDF 页码锚点（P1 · 0.5 人日）

#### 3.1 PDF 按页抽取与页码锚点
1. 改造 [`PdfParser.java`](../src/main/java/com/ltc/rag/parser/PdfParser.java)：
   - 改用循环按页抽取（`stripper.setStartPage(p); stripper.setEndPage(p);`），在拼接全文或产出结构时记录每一页的起始位置（或插入不干扰语义的页边界标记 / 页偏移表 `List<PageSpan>`）。
2. `ChunkPiece` 与 `kb_chunk` 新增 `page_no INT NULL`（记录该分块起始所在的 PDF 页码，从 `1` 开始；非 PDF 格式为 `null`）。
3. `Citation` 记录新增 `Integer page` 字段：
   - 前端 [`index.html`](../src/main/resources/static/index.html) 的引用卡片标题旁若 `c.page != null`，显示 `<span class="tag">P.${c.page}</span>`（或“第 N 页”）。

#### 3.2 解析预检接口 `POST /v1/documents/preview`
1. **定位**：供管理员或 Agent 在正式入库前检验“这份文件解析出来到底干不干净、会切成多少块”，**只走 `ParserRegistry + Chunker` 内存计算，不落数据库、不调 Embedding、不写 Qdrant**。
2. **请求**：`POST /v1/documents/preview`（`multipart/form-data`，参数 `file`）。
3. **响应示例**：
   ```json
   {
     "fileName": "接口规范.pdf",
     "format": "pdf",
     "pages": 18,
     "charCount": 24600,
     "parentChunks": 12,
     "childChunks": 54,
     "tableChunks": 8,
     "codeChunks": 5,
     "warnings": [
       {
         "code": "PDF_TABLE_SUSPECTED",
         "message": "PDF 中检测到疑似多列对齐文本，PDFBox 可能将表格拉平，重要表格建议先用 scripts/pdf2md.ps1 转 Markdown 后上传"
       }
     ],
     "sampleChunks": [
       {
         "index": 0,
         "type": "CHILD",
         "headingPath": "1. 订单接口 > 1.1 创建订单",
         "page": 2,
         "charCount": 380,
         "preview": "1. 订单接口 > 1.1 创建订单\n调用 POST /api/v2/order/create..."
       }
     ]
   }
   ```
4. **内置预检规则（Warnings）**：
   - `NO_HEADING`：全文未识别到任何 Markdown 标题（`#`），提示切分将退化为无章节路径的定长切分；
   - `UNCLOSED_FENCE`：全文代码围栏（` ``` `）数量为奇数，提示存在未闭合代码块；
   - `PDF_TABLE_SUSPECTED`：PDF 格式且多行出现连续空格/制表符分列特征，提示 PDFBox 不做表格结构识别。

---

### 需求 R4 · 可选异步入库兜底（防 Cloudflare 100s 超时 · P1 · 0.5 人日）

#### 4.1 为什么采用“默认同步 + 可选异步”
- 我们的向量化已经迁到了同机 Docker 内网的 `ltc-rag-ocr-1`（CPU `bge-small-zh-v1.5`），常规 50~100 块的文档入库只需 **1~3 秒**，同步返回最简单直接；
- 但当上传 **20MB+ 的多页 PDF 或大文档** 时，经由 `https://rag.932000.xyz`（Cloudflare 代理）可能会在网络传输或长解析上接近 Cloudflare 的 100s 上限。

#### 4.2 轻量异步方案
1. `POST /v1/documents` 增加可选查询参数 `?async=true`（默认 `false`，保持 100% 向后兼容）：
   - 当 `async=false`（默认）：维持现有同步返回 `201 Created {document, created, changed}`；
   - 当 `async=true`：校验文件非空后，立即将入库任务提交到后台 `ragExecutor`，返回 `202 Accepted`：
     ```json
     {
       "jobId": "ing_9f2c8a1b...",
       "status": "RUNNING",
       "pollUrl": "/v1/documents/jobs/ing_9f2c8a1b..."
     }
     ```
2. 状态查询接口 `GET /v1/documents/jobs/{jobId}`：
   - 返回 `{jobId, status: "RUNNING|SUCCEEDED|FAILED", stage: "PARSING|CHUNKING|EMBEDDING|INDEXING|READY", totalChunks, reusedChunks, embeddedChunks, document, error}`。
   - 状态保存在内存 `ConcurrentHashMap`（保留最近 200 条任务记录，并同步反映在 `kb_document.status = 'INDEXING' / 'READY'` 上），**无需为了极低频的大文件上传专门建轮询表和定时扫表线程**，保持系统极简！

---

## 3. 数据库变更（`sql/migrations/003-ingestion-v2-lite.sql`）

只对现有两张表增加 3 个高价值可空字段，**零新表负担、零破坏性变更、100% 兼容已有数据**：

```sql
-- =====================================================================
--  003: Ingestion v2-Lite 迁移脚本（可重复执行）
--  1. kb_document.source_hash : 区分原件字节哈希与 Markdown 内容哈希
--  2. kb_chunk.content_hash   : 分块内容 SHA256，用于原位复用 Qdrant vector_id
--  3. kb_chunk.page_no        : PDF 分块起始页码，用于 Citation 页码回溯
-- =====================================================================

SET @db := DATABASE();

-- 1) kb_document.source_hash
SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_document' AND COLUMN_NAME = 'source_hash'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_document ADD COLUMN source_hash CHAR(64) NULL COMMENT ''原件字节 SHA256'' AFTER content_hash',
    'SELECT ''kb_document.source_hash already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) kb_chunk.content_hash
SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'content_hash'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_chunk ADD COLUMN content_hash CHAR(64) NULL COMMENT ''分块文本 SHA256，用于增量复用 vector_id'' AFTER char_count',
    'SELECT ''kb_chunk.content_hash already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) kb_chunk.page_no
SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'page_no'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE kb_chunk ADD COLUMN page_no INT NULL COMMENT ''PDF 起始页码（从 1 开始）'' AFTER content_hash',
    'SELECT ''kb_chunk.page_no already exists'' AS info');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

（同时将上述 3 个字段同步写入 `sql/schema.sql`，并在应用启动时或部署脚本中自动执行 `003-ingestion-v2-lite.sql`）。

---

## 4. 涉及代码文件清单与改动矩阵

| 序号 | 文件路径 | 改动点说明 | 预估行数 |
| :--- | :--- | :--- | :--- |
| 1 | `sql/schema.sql` & `sql/migrations/003-ingestion-v2-lite.sql` | 增加 `kb_document.source_hash`、`kb_chunk.content_hash`、`kb_chunk.page_no` | +45 行 |
| 2 | `src/main/java/com/ltc/rag/chunk/ChunkPiece.java` | 新增 `CODE` / `TABLE` 类型常量，增加 `String contentHash` 与 `Integer pageNo` 字段 | +20 行 |
| 3 | `src/main/java/com/ltc/rag/chunk/Chunker.java` | 实现 `TEXT / TABLE / CODE` 原子块识别、表格跨块表头自动跟随（Header Propagation）、代码块围栏自愈（Fence Healing）、计算 `contentHash` 与映射 `pageNo` | +130 行 |
| 4 | `src/main/java/com/ltc/rag/parser/DocumentParser.java` & `PdfParser.java` | `PdfParser` 改为按页抽取并记录每页字符偏移 `pageOffsets` | +45 行 |
| 5 | `src/main/java/com/ltc/rag/repo/KnowledgeRepository.java` | • `fulltextSearch()` 过滤条件改为 `c.chunk_type IN ('CHILD', 'CODE', 'TABLE')`<br>• 读写 `content_hash`、`page_no`、`source_hash`<br>• 新增查询某文档现有 `content_hash -> vector_id` 映射方法 | +40 行 |
| 6 | `src/main/java/com/ltc/rag/service/IngestionService.java` | • 拆分 `sourceHash` 与 `contentHash`<br>• 更新文档时对比 `content_hash` 原位复用未变分块的 Qdrant `vector_id`，仅对变更块调 Embedding<br>• 新增 `preview()` 方法与异步 job 包装 | +90 行 |
| 7 | `src/main/java/com/ltc/rag/domain/Citation.java` & `Retriever.java` & `QaService.java` | `Citation` 增加 `Integer page` 字段，检索回表时带出 `page_no` | +20 行 |
| 8 | `src/main/java/com/ltc/rag/api/DocumentController.java` | 新增 `POST /v1/documents/preview`、支持 `?async=true` 与 `GET /v1/documents/jobs/{jobId}` | +45 行 |
| 9 | `src/main/resources/static/index.html` | 引用卡片展示 `第 N 页` 标签；入库完成提示展示“新增/复用向量块数” | +25 行 |
| 10 | `src/test/java/com/ltc/rag/chunk/ChunkerTest.java` | 新增表格跨块表头跟随、长代码块围栏闭合、页码映射单元测试 | +70 行 |

---

## 5. 实施步骤与验收剧本

### 5.1 实施顺序
1. **Step 1（内核切分与单测）**：改 `ChunkPiece`、`PdfParser`、`Chunker`，跑通 `ChunkerTest`（验证 30 行大表格切分后每块带表头、长代码块每块围栏闭合、PDF 页码准确）。
2. **Step 2（数据层与增量复用）**：编写 `003-ingestion-v2-lite.sql`，改 `KnowledgeRepository` 与 `IngestionService`，验证在线修改文档 1 个字时，95%+ 分块直接复用旧 `vector_id`。
3. **Step 3（接口、UI 与线上部署）**：暴露 `/v1/documents/preview` 与 `Citation.page`，更新前端展示，一键同步到 RackNerd 服务器（`https://rag.932000.xyz`）并跑通线上回归测试。

### 5.2 验收标准
1. **查表准确率验证**：上传包含数十张表的 Markdown/Word/图片文档，提问某张处于表格中后部的表（如 `t_daily`），命中的 `TABLE` 分块自带第一行表头，大模型能准确回答每一列的含义。
2. **增量更新零浪费验证**：对一篇 50 块的文档在线编辑修改其中 1 段话并保存，日志输出 `复用旧向量=48 新算向量=2`，耗时 < 500ms。
3. **BM25 全召回验证**：`CHILD / CODE / TABLE` 三类叶子分块在向量检索与 MySQL 全文检索两路均可正常召回。
4. **PDF 页码溯源验证**：上传多页 PDF 后提问，返回的 `citations[]` 中带准确的 `"page": N`，前端引用卡片显示 `第 N 页`。
