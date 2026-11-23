# 文档入库（Ingestion）v2 开发需求文档

| 项 | 内容 |
|---|---|
| 文档版本 | v0.1（待评审） |
| 编写日期 | 2026-11-23 |
| 关联代码 | `src/main/java/com/ltc/rag/service/IngestionService.java`、`api/DocumentController.java`、`chunk/Chunker.java`、`parser/*`、`llm/EmbeddingClient.java`、`repo/KnowledgeRepository.java` |
| 关联表 | `kb_document`、`kb_chunk`、`kb_api_key` |
| 影响范围 | 上传接口契约、入库流水线、数据模型、检索过滤 |
| 目标读者 | 后端开发、测试、后续接手的 Agent |

---

## 1. 背景

### 1.1 一句话

现在的"上传"是一个**同步的、全量的、无留存的一次性动作**：`POST /v1/documents` 在同一个 HTTP 请求里把"解析 → 切分 → 向量化 → 写 Qdrant → 覆盖写 MySQL"跑完，原件不落盘、旧版本不留痕、没有进度也没有重试。

它能支撑"我自己丢几份文档随便问问"，支撑不了 README 第一行写的目标：**让别的 Agent 敢调用、能校验、可重试**。

### 1.2 现状量化

| 维度 | 现状 | 后果 |
|---|---|---|
| 原件 | 只存解析后的 Markdown（`kb_document.raw_content`），**原始 docx/pdf 丢弃** | 换解析器（Docling/MinerU/按页 PDF）无法重跑历史，只能让用户重传 |
| 时延 | 同步阻塞，一次请求跑完全链路（[DocumentController.java:59](../src/main/java/com/ltc/rag/api/DocumentController.java#L59)） | 300 chunk 文档 = 30 次 embedding 调用（batch=10），云端 15~60s，代理 60s 默认直接 504 |
| 增量 | 改一个字 → 整篇重新 embedding（[IngestionService.java:148](../src/main/java/com/ltc/rag/service/IngestionService.java#L148)） | 90% 的 API 调用和费用是浪费 |
| 版本 | `deleteChunks` 与插新块同事务，旧内容物理删除（[IngestionService.java:164](../src/main/java/com/ltc/rag/service/IngestionService.java#L164)） | 无法回滚、无法 diff、无法回答"这条答案基于哪一版" |
| 权限 | 只有 `namespace`（[Retriever.java:58](../src/main/java/com/ltc/rag/service/Retriever.java#L58)），无文档级 ACL | 知识库对多个 Agent 开放时，无权限隔离（库内已有《客户等级与授信规则》这类文档） |
| 去重 | 幂等键是 `(namespace, docKey)`，`docKey` 缺省取文件名 | 同名换 key、或改名重传 → 内容重复、向量重复、检索互相稀释 |
| 可观测 | 问答有 `kb_qa_log`，入库没有对应表 | 答不上"谁传的、哪一阶段慢、失败几次" |
| 质量门禁 | 无预览、无解析报告 | 版式抽错的 PDF 静默入库，上线后检索差才发现 |

### 1.3 为什么现在做

1. **要部署到公网 VPS 了**：小内存机器上，同步解析大 PDF 直接 OOM，代理超时会把"上传成功但实际回滚"变成常态。
2. **要接多个 Agent**：没有 ACL 和版本，就不是"知识服务"，只是一个能被任意调用的全文检索接口。
3. **解析器一定会升级**：原件不留存，等于每次升级都要人工重传一遍全库——这是一次性设计债，越晚还越贵。

---

## 2. 目标与非目标

### 2.1 目标

- **G1 可回溯**：任何一条引用都能回到"哪份原件、哪个版本、第几页"。
- **G2 可重试**：入库是幂等任务，失败能重跑，重跑不重复计费。
- **G3 可增量**：内容变化只重算变化的分块，重复内容零成本。
- **G4 可治理**：文档级权限、元数据、版本历史、入库审计四件套齐全。
- **G5 不破坏现状**：老接口、老数据、老前端在迁移期继续可用。

### 2.2 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| 引入 MQ / Redis / ES | 3C4G 上每多一个中间件就多一份不稳定；继续用 `SKIP LOCKED` 轮询 |
| 视觉模型解析 PDF 版面 | 成本与延迟不划算，离线 Docling 转换仍是推荐路径 |
| 在线爬取 / 定时抓取远端站点 | 属于"数据接入"，与本次"入库治理"解耦，放 P2 |
| 多租户计费、配额系统 | 与本次目标无关 |
| 前端重写 | 只改"上传 → 轮询进度"这一块交互，其余页面不动 |

---

## 3. 术语

| 术语 | 定义 |
|---|---|
| **原件（source）** | 用户上传的原始字节（docx/pdf/xlsx/png…），v2 起必须留存 |
| **Markdown 派生视图** | 解析器产出的规范化 Markdown，可随时由原件重算 |
| **分块（chunk）** | 检索与引用的最小单位，`PARENT / CHILD / CODE / TABLE` 四类 |
| **入库任务（ingest job）** | 一次"原件 → 可用分块"的异步执行单元，有状态机与进度 |
| **版本（version）** | 同一 `(namespace, docKey)` 的一次成功入库结果，只增不删 |
| **`source_hash`** | 原件字节的 SHA256（v2 新增，回答"这份文件是否还是同一份"） |
| **`content_hash`** | 规范化 Markdown 的 SHA256（沿用，回答"内容有没有变，能否跳过重建"） |
| **块哈希（`chunk_hash`）** | 单个分块文本（含 headingPath）的 SHA256，向量缓存的键 |

> ⚠️ **现状缺陷**：当前 `content_hash` 语义不一致——上传路径算的是**文件字节**哈希（[IngestionService.java:101](../src/main/java/com/ltc/rag/service/IngestionService.java#L101)），在线编辑路径算的是 **Markdown 文本**哈希（[IngestionService.java:250](../src/main/java/com/ltc/rag/service/IngestionService.java#L250)）。v2 必须拆成两个字段，否则"跳过重建"的判断在两种入口下含义不同。

---

## 4. 现状基线（改造前）

### 4.1 现有接口

| 方法 | 路径 | 行为 | 权限 |
|---|---|---|---|
| POST | `/v1/documents` | multipart 上传，**同步**返回入库结果 | ADMIN |
| POST | `/v1/documents/text` | 直接提交 Markdown，同步 | ADMIN |
| GET | `/v1/documents?namespace=&limit=` | 列表 | 任意 Key |
| GET | `/v1/documents/{id}/content` | 取原文 Markdown | 任意 Key |
| PUT | `/v1/documents/{id}/content` | 全文替换并重建 | ADMIN |
| POST | `/v1/documents/{id}/append` | 追加并重建 | ADMIN |
| POST | `/v1/documents/reindex?namespace=` | 按原文全量重建 | ADMIN |
| DELETE | `/v1/documents/{id}` | 删除文档与向量 | ADMIN |

### 4.2 现有入库时序（v1）

```
Client ──POST /v1/documents (multipart, ≤40MB)──▶ DocumentController
                                                   │  同一线程同步执行
                                                   ├─ ① file.getBytes()           全量进堆
                                                   ├─ ② ParserRegistry.parse()    扩展名路由
                                                   ├─ ③ Chunker.split()           标题感知
                                                   ├─ ④ EmbeddingClient.embedAll() 批10，重试3
                                                   ├─ ⑤ QdrantStore.upsert()
                                                   └─ ⑥ TX: 删旧块 → 插新块 → 置 READY
Client ◀── 201 {document, created, changed} ───────┘
```

### 4.3 已有优点（改造中必须保留）

- **一致性策略是对的**：先置 `INDEXING`（检索层过滤）→ 写向量 → 单事务换块 → 置 `READY`；失败回滚向量、恢复旧数据。v2 的异步 worker 必须原样复用这套顺序，不要因为异步化而放松。
- **失败要响**：embedding 不静默跳过（[EmbeddingClient.java:24](../src/main/java/com/ltc/rag/llm/EmbeddingClient.java#L24)），维度不一致直接报错。
- **父子块 + 标题路径**：检索命中子块能回填父块，引用带 `headingPath`。

---

## 5. 需求总览

| 编号 | 需求 | 优先级 | 预估 | 依赖 |
|---|---|---|---|---|
| R1 | 原件留存与下载 | P0 | 1.0d | — |
| R2 | 异步入库任务与进度 | P0 | 1.5d | R1 |
| R3 | 解析预检（Preview / Parse Report） | P0 | 1.0d | — |
| R4 | 块哈希 + 向量缓存（增量重建） | P0 | 1.5d | R2 |
| R5 | 版本历史与回滚 | P1 | 1.0d | R1 |
| R6 | 元数据与文档级 ACL | P1 | 2.0d | R5 |
| R7 | 全局内容去重 | P1 | 0.5d | R1 |
| R8 | 表格 / 代码块感知切分 | P1 | 1.5d | — |
| R9 | PDF 页码锚点 | P1 | 1.0d | R8 |
| R10 | 入库审计与埋点 | P1 | 0.5d | R2 |
| R11 | 批量 / 远端源接入 | P2 | 3.0d | R2 |
| — | **合计** | | **≈12 人日**（P0+P1；R11 本期仅出设计，不计入） | |

> 建议分两期：**P0（5 人日）先上线**，解决"会炸"的问题；P1 解决"敢调用"的治理问题。

---

## 6. 详细需求

### R1 · 原件留存与下载（P0）

**背景**：原件是唯一不可再生的资产，Markdown 和向量都是派生结果。

**需求**

1. 新增 `ObjectStore` 抽象：`put(key, bytes) / get(key) / exists(key) / delete(key)`。
   - `LocalObjectStore`：默认实现，落 `rag.storage.object-dir`（默认 `./objects`），按 `namespace/docKey/` 分目录。
   - `S3ObjectStore`：P2 再做（MinIO / OSS 兼容 S3 协议）。
2. 对象键规则：`{namespace}/{docKey}/{version}-{source_hash前8}.{ext}`。**版本不同不覆盖**，便于回滚与审计。
3. 入库成功后写 `kb_document_version`（见 R5），`object_key` 指向原件。
4. 新增 `GET /v1/documents/{id}/versions/{version}/download`：ADMIN 可下载任意历史版本原件；AGENT 仅可下载当前 READY 版本。
5. 原件大小上限沿用 `MAX_UPLOAD_MB=40`；写入前校验 `source_hash` 与落盘后回读一致。

**验收标准**

- [ ] 上传 `demo.docx` 后，`{object-dir}/ltc/demo/1-xxxxxxxx.docx` 存在，SHA256 与上传字节一致。
- [ ] `GET /v1/documents/{id}/versions/1/download` 返回的 Content-Disposition 文件名正确。
- [ ] 删除文档（`DELETE /v1/documents/{id}`）时，原件不立即物理删除，标记为 `ORPHANED`，由清理任务按 `rag.version.keep` 处理。

---

### R2 · 异步入库任务与进度（P0）

**背景**：同步路径必然被代理超时掐断，且大文档占用 Web 线程。

**需求**

1. `POST /v1/documents` 默认改为**异步**：
   - 落原件 → 建 `kb_ingest_job`（`PENDING`）→ 立即返回 `202 {jobId, pollUrl}`。
   - 保留 `?sync=true` 走旧同步路径（兼容现有脚本与前端，迁移期后废弃）。
2. 状态机：

   ```
   PENDING ──claim──▶ PARSING ──▶ CHUNKING ──▶ EMBEDDING ──▶ INDEXING ──▶ READY
                         │            │             │             │
                         └────────────┴─────────────┴─────────────┴──▶ FAILED
                                                                        │
                                                            POST /retry └──▶ PENDING
   PENDING ──cancel──▶ CANCELLED
   ```

3. Worker：复用 `kb_task` 的 `FOR UPDATE SKIP LOCKED` 抢占模式，不引入 MQ。
   - `rag.ingest.workers`（默认 `1`）：3C4G 上必须为 1，避免并发解析叠加内存。
   - `rag.ingest.queue-capacity`（默认 `200`）：队列满时新提交返回 `429 INGEST_QUEUE_FULL`。
   - 轮询间隔 `rag.ingest.poll-interval-ms`（默认 `2000`）。
4. 进度：`progress` 为 0~100 的整数，按"已向量化分块 / 总分块"推进；`stage` 枚举与状态机一致。
5. 幂等：请求头 `Idempotency-Key`（或 `(namespace, docKey, source_hash)` 三元组）在 30 分钟内重复提交 → 返回**同一个** `jobId`，不重复建任务。
6. 重试：`POST /v1/ingest-jobs/{jobId}/retry`，`attempts < 3` 才允许；重试复用同一 job，不新建。
7. 取消：仅 `PENDING` 可取消；`RUNNING` 返回 `409 JOB_RUNNING`。
8. 兼容：`GET /v1/documents` 增加 `ingestStatus` 字段（`NONE / RUNNING / FAILED`），前端能看到"这篇正在入库"。

**验收标准**

- [ ] 40MB PDF 上传接口 P95 < 300ms（只落件 + 建 job）。
- [ ] `GET /v1/ingest-jobs/{id}` 在入库过程中能看到 `stage` 从 `PARSING` 推进到 `READY`，`progress` 单调不减。
- [ ] 同一 `Idempotency-Key` 连发 3 次，只产生 1 条 `kb_ingest_job`。
- [ ] 杀掉应用进程后重启，`PENDING / RUNNING` 的 job 自动恢复（`RUNNING` 超时 10 分钟回到 `PENDING`）。
- [ ] `?sync=true` 的旧路径行为与 v1 完全一致。

---

### R3 · 解析预检（P0）

**背景**：现在"抽错版式"只有上线后才发现，且没有"先看再入库"的机会。

**需求**

1. 新增 `POST /v1/documents/preview`（multipart，**不落库、不建 job、不产生向量**），返回解析报告：

   ```json
   {
     "format": "pdf",
     "bytes": 3145728,
     "pages": 42,
     "chars": 68210,
     "headings": 37,
     "codeBlocks": 0,
     "tables": 6,
     "imagesDropped": 4,
     "estimatedChunks": 158,
     "estimatedTokens": 47000,
     "warnings": [
       {"code": "PDF_TABLE_FLATTENED", "message": "检测到 6 处疑似表格，PDFBox 会拉平成文本，建议用 Docling 转 Markdown 后入库"}
     ],
     "preview": [
       {"index": 0, "type": "CHILD", "headingPath": "接口规范 > 订单", "chars": 412, "excerpt": "……"}
     ]
   }
   ```

2. 警告码表：

   | code | 触发条件 | 提示 |
   |---|---|---|
   | `PDF_NO_TEXT_LAYER` | 平均每页 < 30 字符 | 扫描件，先 OCR |
   | `PDF_TABLE_FLATTENED` | 连续 3 行以上以竖线或制表符对齐 | 建议 Docling |
   | `NO_HEADING` | 全文无 `#` 标题 | 切分将退化为定长，建议补标题 |
   | `UNCLOSED_FENCE` | 围栏标记数量为奇数 | 代码块会污染后续切分 |
   | `LOW_QUALITY_CHARS` | 非常用字符占比 > 20% | 疑似乱码/加密文档 |
   | `IMAGE_DROPPED` | PDF/Word 内含图片 | 图片内容不会入库 |

3. `estimatedChunks` 与实际入库 `chunkCount` 的误差需 ≤ ±10%（同一 parser + chunker 配置下）。

**验收标准**

- [ ] 扫描件 PDF preview 返回 `422 PDF_NO_TEXT_LAYER` 且带温馨提示。
- [ ] preview 结束后 `kb_document` / `kb_chunk` / Qdrant 均无新增数据。
- [ ] 前端"拖入文件 → 显示报告 → 确认入库"流程可走通（前端改动见 §8.4 与 §16.1）。

---

### R4 · 块哈希 + 向量缓存（P0）

**背景**：改一个字重算全篇是最大的成本浪费；跨文档重复内容（模板、接口表）也没复用。

**需求**

1. `kb_chunk` 增加 `content_hash CHAR(64)`：对 `headingPath + "\n" + content` 的 UTF-8 字节取 SHA256。
2. 新增缓存表 `kb_embedding_cache`（见 §7.3），主键 `(content_hash, model)`，向量以 `MEDIUMBLOB` 存 float32 小端数组。
3. 入库流程改为：

   ```
   chunks → 计算 chunk_hash → 批量查缓存
                          ├─ 命中：直接取向量（不调 API）
                          └─ 未命中：按 batch 调 API，回写缓存
   ```

4. 缓存键包含 `model` 与 `dimension`：换模型自动不命中，无需手动清表。
5. 提供 `rag.embedding.cache-max-rows`（默认 200000）与 LRU 清理任务（每小时一次，按 `last_hit_at` 淘汰）。
6. `job.timings` 增加 `embeddingCalls` / `cacheHits` / `embeddingMs`，便于验证收益。

**成本测算（写进验收）**

| 场景 | 改造前 API 调用 | 改造后 |
|---|---|---|
| 300 chunk 文档首次入库 | 30 | 30（无缓存可用） |
| 改 1 段后重传（约 3 块变化） | 30 | **1** |
| 同模板的第二份文档（30% 重复块） | 30 | **21** |
| 第 2 次全库 `reindex` | 30 × 文档数 | **0** |

**验收标准**

- [ ] 300 chunk 文档改 3 块后重传，`job.timings.embeddingCalls ≤ 2`。
- [ ] 同一内容连续入库两次，第二次 `cacheHits == 总块数`。
- [ ] 换 `EMBEDDING_MODEL` 后不复用旧向量（`cacheHits == 0`）。
- [ ] 缓存表向量回读后与 API 直返结果逐位相等（容差 0）。

---

### R5 · 版本历史与回滚（P1）

**背景**：知识库的内容会被上游改错，没有"上一版"就无法追责与恢复。

**需求**

1. 新增 `kb_document_version`（见 §7.1），每次成功入库插一行，**只增不删**。
2. 保留策略：`rag.version.keep`（默认 10）个版本的 `raw_content` 快照；超出后只保留元数据行（原件仍按 `object_key` 留存，或标记 `OBJECT_PRUNED`）。
3. 接口：

   | 方法 | 路径 | 说明 |
   |---|---|---|
   | GET | `/v1/documents/{id}/versions` | 版本列表（version / chunkCount / sourceHash / createdAt / createdBy） |
   | GET | `/v1/documents/{id}/versions/{v}/content` | 该版本 Markdown |
   | POST | `/v1/documents/{id}/rollback` | body `{"version": 2}`，回到历史版本 |
   | GET | `/v1/documents/{id}/versions/{v}/download` | 该版本原件（R1） |

4. 回滚语义：**不删除历史**，而是用 v2 内容走一次 `replaceContent(force=true)`，生成新版本号（例如 v1→回滚→v4）。历史表保留 v1~v3。
5. `kb_document.version` 与 `kb_document_version.version` 必须一致（同一事务内写入）。

**验收标准**

- [ ] 连续入库 3 次后 `GET versions` 返回 3 条，倒序。
- [ ] 回滚到 v1 后，检索命中 v1 内容，`kb_document.version == 4`，历史仍有 v1~v3。
- [ ] 超过 `keep` 的版本，`content` 接口返回 `410 VERSION_CONTENT_PRUNED` 而不是 500。

---

### R6 · 元数据与文档级 ACL（P1）

**背景**：现在只有 `namespace`，无法回答"这个 Agent 能不能看这份文档"。

**需求**

1. `kb_document` 增加：`acl_groups JSON`、`effective_from DATE`、`effective_to DATE`、`tags JSON`、`source_type VARCHAR(16)`。
2. `kb_api_key` 增加：`acl_groups JSON`（默认 `["public"]`）。
3. 默认值：新文档 `acl_groups = ["public"]`；旧数据迁移时统一 backfill 为 `["public"]`（保证上线后召回不下降）。
4. 检索过滤（**必须在两路都做**，缺一路就是越权）：
   - Qdrant：`filter.must = [{key: "acl_groups", match: {any: callerGroups}}]`，payload 写入时同步 `acl_groups`。
   - MySQL 全文：`WHERE JSON_OVERLAPS(kb_document.acl_groups, ?)`。
5. `ADMIN` Key 不过滤（可见全部）；`AGENT` Key 按 `acl_groups` 过滤。
6. 文档列表 `/v1/documents` 同步按 ACL 过滤。

**验收标准**

- [ ] groupA 的 Key 提问 → citations 不含 groupB 文档。
- [ ] groupA 的 Key 调 `/v1/documents` → 列表不含 groupB。
- [ ] `effective_to` 已过期的文档不参与检索（按当前日期过滤）。
- [ ] 旧数据 backfill 后，原有用例的 recall 不变（用 README §10 的质量基线跑一遍）。

---

### R7 · 全局内容去重（P1）

**背景**：同一份文档换 `docKey` 重传 → 两份向量，检索时互相稀释。

**需求**

1. `kb_document.content_hash` 加普通索引 `idx_content_hash`。
2. 入库前检查：若 `source_hash`（或 `content_hash`）已存在于**其他 `docKey`**：
   - 默认返回 `409 DUPLICATE_CONTENT`，body 里给 `{existingDocId, existingDocKey, title, updatedAt}`；
   - 调用方显式传 `?allowDuplicate=true`（或表单字段）才继续入库。
3. `?sync=true` 路径同样生效（错误码一致）。
4. 提供 `POST /v1/documents/dedupe?namespace=`：扫描重复组，返回候选清单，**只报告不自动合并**（合并涉及引用重写，风险高，另立需求）。

**验收标准**

- [ ] 同一文件用不同 `docKey` 上传 → 409，body 含候选 docId。
- [ ] 加 `allowDuplicate=true` → 201，两份并存。
- [ ] 不同 `namespace` 之间**不**触发去重（允许同一份规范存在于 ltc 与 ltc-v2.3）。

---

### R8 · 表格 / 代码块感知切分（P1）

**背景**：现状 `splitLong` 按段落→句子→硬切（[Chunker.java:128](../src/main/java/com/ltc/rag/chunk/Chunker.java#L128)），对表格和代码块完全无感知。`readSections` 只解决了"代码块里的 `#` 不是标题"。

**现状缺陷（必须在文档中记录，避免被当作已实现）**

- 600 字的代码块被切成两半，围栏开合错位 → 引用里渲染成乱码，向量语义也被破坏。
- Markdown 表格从行中间切断，表头与数据分离 → "订单超时重试几次"这类问题直接查不到。

**需求**

1. 切分前先做**原子块识别**（扫描 fenced code 与 markdown table），产出三类单元：`TEXT`、`CODE`、`TABLE`。
2. 切分策略：

   | 类型 | 策略 |
   |---|---|
   | `CODE` | 整块不拆；超过 `max-child-chars` 时按空行切，**每段补围栏（带语言标记）**，块标记为 `CODE` |
   | `TABLE` | 整表不拆；超过预算按行切，**每块重复表头 + 首列**，块标记为 `TABLE` |
   | `TEXT` | 沿用现有段落/句子/overlap 逻辑 |

3. `chunk_type` 枚举扩展为 `PARENT / CHILD / CODE / TABLE`（列宽 8 已够，无需改表）。
4. 引用展示：`CODE` 块在 UI 里用等宽字体渲染，`TABLE` 块渲染成表格。

**验收标准**

- [ ] 含 30 行表格的 md 入库后，每个 TABLE 块的第一行都是表头。
- [ ] 含 600 字代码块的 md 入库后，任意块内围栏标记数量为偶数。
- [ ] 父块仍只由章节级长文本产生，CODE/TABLE 不生成父块。
- [ ] 现有 `ChunkerTest` 全部通过，新增 CODE/TABLE 用例 ≥ 6 条。

---

### R9 · PDF 页码锚点（P1）

**背景**：`PDFTextStripper.getText()` 一次性抽全文（[PdfParser.java:42](../src/main/java/com/ltc/rag/parser/PdfParser.java#L42)），页码信息丢失，引用只能给 `headingPath`。

**需求**

1. `PdfParser` 改为按页抽取（`setStartPage / setEndPage` 循环），维护"字符偏移 → 页码"映射。
2. `DocumentParser.Parsed` 增加 `pageOffsets`（或断点数组）；`ChunkPiece` 增加 `pageNo`（块起始页）。
3. `kb_chunk` 增加 `page_no INT NULL`；`Citation` 增加 `page` 字段（可为 null，兼容非 PDF）。
4. 引用卡片展示"第 N 页"；未来可扩展 `bbox` 做原文高亮（本期不做）。

**验收标准**

- [ ] 42 页 PDF 入库后，第 2 页起始的块 `page_no == 2`。
- [ ] `/v1/qa` 返回的 citation 中，PDF 来源带 `page`，md 来源为 `null`。
- [ ] 按页解析后的全文，与旧实现抽出的全文做归一化后**字符数偏差 ≤ 1%**（换行差异允许）。

---

### R10 · 入库审计与埋点（P1）

**需求**

1. 新增 `kb_ingest_log`（见 §7.4）：每次 job 结束写一行，记录各阶段耗时。
2. `job.timings` JSON 至少包含：`parseMs / chunkMs / embeddingMs / qdrantMs / dbMs / totalMs`。
3. `/v1/admin/stats` 增加入库维度：近 30 天入库数、失败数、平均耗时、失败 top 原因。
4. 所有失败必须落 `error`（≤ 2000 字符，含 `stage` 前缀，如 `EMBEDDING: connection refused`）。

**验收标准**

- [ ] 任意 job 都能在 `kb_ingest_log` 查到对应行，且 `sum(stage_ms)` 与 `total_ms` 偏差 < 10%。
- [ ] `/v1/admin/stats` 返回 `ingest` 段落，字段齐全。

---

### R11 · 批量与远端源（P2，本期只做接口设计）

- `POST /v1/documents:batch`：multipart 多文件或 zip 包，返回 `{jobs: [jobId]}`。
- 目录同步：给定 S3/OSS 前缀或 Git 仓库路径，按 `docKey` 规则增量同步。
- 本期**只出接口设计与表结构预埋**（`source_type` 字段已留），不实现。

---

## 7. 数据模型变更

> 迁移脚本：`sql/migrations/003-ingestion-v2.sql`，写法照抄 `002-raw-content.sql`（可重复执行、列存在即跳过）。

### 7.1 `kb_document_version`（新增）

```sql
CREATE TABLE IF NOT EXISTS kb_document_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id          BIGINT       NOT NULL,
    version         INT          NOT NULL COMMENT '与 kb_document.version 同步递增',
    source_hash     CHAR(64)     NOT NULL COMMENT '原件字节 SHA256',
    content_hash    CHAR(64)     NOT NULL COMMENT '规范化 Markdown SHA256',
    object_key      VARCHAR(512)          DEFAULT NULL COMMENT '原件在对象存储中的键',
    object_bytes    BIGINT                DEFAULT NULL COMMENT '原件字节数',
    parser_name     VARCHAR(64)           DEFAULT NULL COMMENT '如 pdfbox / docling',
    parser_version  VARCHAR(32)           DEFAULT NULL,
    chunk_count     INT          NOT NULL DEFAULT 0,
    char_count      INT          NOT NULL DEFAULT 0,
    raw_content     MEDIUMTEXT            DEFAULT NULL COMMENT '该版本 Markdown 快照（超出保留策略后置空）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY/ORPHANED/OBJECT_PRUNED',
    created_by      BIGINT                DEFAULT NULL COMMENT 'kb_api_key.id',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_version (doc_id, version),
    KEY idx_source_hash (source_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '文档版本快照';
```

### 7.2 `kb_ingest_job`（新增）

```sql
CREATE TABLE IF NOT EXISTS kb_ingest_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_id          CHAR(32)     NOT NULL COMMENT '对外暴露的任务号',
    api_key_id      BIGINT                DEFAULT NULL,
    namespace       VARCHAR(64)  NOT NULL,
    doc_key         VARCHAR(191) NOT NULL,
    title           VARCHAR(255)          DEFAULT NULL,
    source_type     VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD/TEXT/EDIT/APPEND/REINDEX',
    file_name       VARCHAR(255)          DEFAULT NULL,
    source_hash     CHAR(64)              DEFAULT NULL,
    object_key      VARCHAR(512)          DEFAULT NULL,
    idempotency_key VARCHAR(128)          DEFAULT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED',
    stage           VARCHAR(16)           DEFAULT NULL COMMENT 'PARSING/CHUNKING/EMBEDDING/INDEXING',
    progress        INT          NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    embedding_calls INT          NOT NULL DEFAULT 0,
    cache_hits      INT          NOT NULL DEFAULT 0,
    timings         JSON                  DEFAULT NULL,
    error           VARCHAR(2000)         DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at      DATETIME(3)           DEFAULT NULL,
    finished_at     DATETIME(3)           DEFAULT NULL,
    heartbeat_at    DATETIME(3)           DEFAULT NULL COMMENT 'RUNNING 超时回收依据',
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_id (job_id),
    UNIQUE KEY uk_idem (idempotency_key),
    KEY idx_status (status, id),
    KEY idx_doc (namespace, doc_key, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '入库任务';
```

### 7.3 `kb_embedding_cache`（新增）

```sql
CREATE TABLE IF NOT EXISTS kb_embedding_cache (
    content_hash CHAR(64)    NOT NULL COMMENT 'chunk 文本(含 headingPath) SHA256',
    model        VARCHAR(64) NOT NULL,
    dimension    INT         NOT NULL,
    vector       MEDIUMBLOB  NOT NULL COMMENT 'float32 小端拼接，长度 = dimension*4',
    hit_count    INT         NOT NULL DEFAULT 1,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_hit_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (content_hash, model),
    KEY idx_last_hit (last_hit_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '向量缓存';
```

> **容量提醒**：1024 维 × 4B = 4KB/行。20 万块 ≈ 800MB，会与 `raw_content` 一起把 MySQL 撑大。上线前必须确认 `rag.embedding.cache-max-rows` 与磁盘余量；备选方案见 §14 风险 R-2。

### 7.4 `kb_ingest_log`（新增）

```sql
CREATE TABLE IF NOT EXISTS kb_ingest_log (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    job_id           CHAR(32)    NOT NULL,
    api_key_id       BIGINT               DEFAULT NULL,
    doc_id           BIGINT               DEFAULT NULL,
    doc_key          VARCHAR(191)         DEFAULT NULL,
    namespace        VARCHAR(64)          DEFAULT NULL,
    ok               TINYINT(1)  NOT NULL,
    parse_ms         INT                  DEFAULT NULL,
    chunk_ms         INT                  DEFAULT NULL,
    embedding_ms     INT                  DEFAULT NULL,
    qdrant_ms        INT                  DEFAULT NULL,
    db_ms            INT                  DEFAULT NULL,
    total_ms         INT                  DEFAULT NULL,
    chunk_count      INT                  DEFAULT NULL,
    embedding_calls  INT                  DEFAULT NULL,
    cache_hits       INT                  DEFAULT NULL,
    error            VARCHAR(2000)        DEFAULT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_job (job_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '入库审计';
```

### 7.5 既有表加列

```sql
-- kb_document：原件与治理字段
ALTER TABLE kb_document
    ADD COLUMN source_hash   CHAR(64)     NULL COMMENT '原件字节 SHA256' AFTER content_hash,
    ADD COLUMN object_key    VARCHAR(512) NULL COMMENT '当前版本原件键' AFTER source_hash,
    ADD COLUMN parser_name   VARCHAR(64)  NULL AFTER object_key,
    ADD COLUMN parser_version VARCHAR(32) NULL AFTER parser_name,
    ADD COLUMN acl_groups    JSON         NULL COMMENT '可读分组，默认 ["public"]' AFTER metadata,
    ADD COLUMN effective_from DATE        NULL AFTER acl_groups,
    ADD COLUMN effective_to   DATE        NULL AFTER effective_from,
    ADD COLUMN tags          JSON         NULL AFTER effective_to,
    ADD COLUMN source_type   VARCHAR(16)  NULL COMMENT 'UPLOAD/TEXT/EDIT/SYNC' AFTER tags,
    ADD KEY idx_content_hash (content_hash);

-- kb_chunk：块哈希与页码
ALTER TABLE kb_chunk
    ADD COLUMN content_hash CHAR(64) NULL COMMENT '块文本 SHA256，向量缓存键' AFTER content,
    ADD COLUMN page_no      INT      NULL COMMENT 'PDF 起始页，非 PDF 为 NULL' AFTER content_hash,
    ADD KEY idx_chunk_hash (content_hash);

-- kb_api_key：调用方可见分组
ALTER TABLE kb_api_key
    ADD COLUMN acl_groups JSON NULL COMMENT '默认 ["public"]' AFTER role;
```

> **语义拆分提醒**：`kb_document.content_hash` 的历史值里混着"文件字节哈希"和"Markdown 哈希"两种。迁移脚本需一次性重算（按 `raw_content` 是否为空判断来源），否则上线后"跳过重建"的判断会错。

---

## 8. 接口契约

### 8.1 变更清单

| 接口 | 类型 | 说明 |
|---|---|---|
| `POST /v1/documents` | **变更** | 默认 202 异步；`?sync=true` 保持 v1 行为 |
| `GET /v1/ingest-jobs/{jobId}` | 新增 | 进度查询 |
| `GET /v1/ingest-jobs?status=&limit=` | 新增 | 任务列表（ADMIN） |
| `POST /v1/ingest-jobs/{jobId}/retry` | 新增 | 失败重试 |
| `POST /v1/ingest-jobs/{jobId}/cancel` | 新增 | 取消（仅 PENDING） |
| `POST /v1/documents/preview` | 新增 | 解析预检，不落库 |
| `GET /v1/documents/{id}/versions` | 新增 | 版本列表 |
| `GET /v1/documents/{id}/versions/{v}/content` | 新增 | 版本原文 |
| `GET /v1/documents/{id}/versions/{v}/download` | 新增 | 版本原件下载 |
| `POST /v1/documents/{id}/rollback` | 新增 | 回滚 |
| `PATCH /v1/documents/{id}` | 新增 | 改元数据 / ACL |
| `POST /v1/documents/dedupe` | 新增 | 重复扫描（只报告） |
| `GET /v1/documents` | 变更 | 增加 `ingestStatus`，按 ACL 过滤 |
| `GET /v1/qa`、`/v1/qa/stream`、`/v1/chat/completions` | 变更 | citation 增加 `page`，检索增加 ACL 过滤 |

### 8.2 示例

**上传（异步，默认）**

```http
POST /v1/documents
X-API-Key: ltc_xxx
Idempotency-Key: 8f3a-4c21
Content-Type: multipart/form-data

file=@接口规范.docx
docKey=ltc/design/api-spec
namespace=ltc
title=LTC 接口规范
url=https://git.internal/ltc/docs/api-spec
aclGroups=groupA,groupB
```

```http
202 Accepted
{
  "jobId": "0f9c2d7e8a1b4c6d9e2f3a5b7c8d1e4f",
  "status": "PENDING",
  "pollUrl": "/v1/ingest-jobs/0f9c2d7e8a1b4c6d9e2f3a5b7c8d1e4f",
  "documentId": 13,
  "deduplicated": false
}
```

**进度**

```json
{
  "jobId": "0f9c2d7e8a1b4c6d9e2f3a5b7c8d1e4f",
  "status": "RUNNING",
  "stage": "EMBEDDING",
  "progress": 62,
  "attempts": 1,
  "chunksTotal": 158,
  "chunksDone": 98,
  "embeddingCalls": 7,
  "cacheHits": 24,
  "timings": {"parseMs": 1830, "chunkMs": 42, "embeddingMs": 6400},
  "createdAt": "2026-11-23T16:40:12.331",
  "startedAt": "2026-11-23T16:40:12.998"
}
```

**失败**

```json
{
  "jobId": "0f9c…",
  "status": "FAILED",
  "stage": "EMBEDDING",
  "attempts": 3,
  "error": "EMBEDDING: Embedding 请求失败：I/O error on POST ... (第 3/3 次)",
  "finishedAt": "2026-11-23T16:41:03.220"
}
```

### 8.3 新增错误码

| HTTP | code | 触发 |
|---|---|---|
| 202 | — | 异步入库已受理 |
| 409 | `DUPLICATE_CONTENT` | 同内容已存在其他 docKey（未加 `allowDuplicate`） |
| 409 | `JOB_RUNNING` | 对 RUNNING 任务调 cancel |
| 410 | `VERSION_CONTENT_PRUNED` | 历史版本快照已按保留策略清理 |
| 422 | `PDF_NO_TEXT_LAYER` | 扫描件（沿用现有，preview 也返回） |
| 429 | `INGEST_QUEUE_FULL` | 队列已满 |
| 429 | `INGEST_RATE_LIMITED` | 单 Key 每分钟入库次数超限（`rag.ingest.rate-per-minute`，默认 20） |

### 8.4 兼容策略

1. **接口层面**：`?sync=true` 完整保留 v1 语义与响应体（`{document, created, changed}`），至少保留两个版本周期。
2. **前端**：默认走异步 + 轮询；轮询失败 3 次后回退到 `?sync=true`（渐进降级，避免用户卡住）。
3. **Agent 调用方**：README 的 curl 示例需要标注"v2 起返回 202"，并提供 `pollUrl` 的轮询示例。
4. **数据层面**：新表新列均为 additive，旧代码在回滚后仍可读（`raw_content` 不动）。

---

## 9. 状态机与时序

### 9.1 目标时序（v2）

```
Client ──①POST /v1/documents────────────────────────────▶ Controller
                                                            ├─ 校验 + 计算 source_hash
                                                            ├─ ObjectStore.put()         落原件
                                                            ├─ INSERT kb_ingest_job (PENDING)
                                                            └─ 202 {jobId}
Worker ──②claim (FOR UPDATE SKIP LOCKED)──▶ PARSING ─▶ CHUNKING ─▶ EMBEDDING ─▶ INDEXING ─▶ READY
                │                              │           │            │            │
                │                              │           │            │            └─ TX: 换块 + 置 READY + INSERT version
                │                              │           │            └─ 缓存优先，未命中才调 API，回写 cache
                │                              │           └─ CONTENT/TABLE/CODE 感知 + chunk_hash
                │                              └─ ParserRegistry（扩展名 + magic bytes）
                └─ heartbeat_at 每 5s 更新
Client ──③GET /v1/ingest-jobs/{id}──▶ {stage, progress, timings}
```

### 9.2 Worker 与多实例

- `claim` 语句：`SELECT ... FROM kb_ingest_job WHERE status='PENDING' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED`（与 `TaskWorker` 同款）。
- 心跳：`RUNNING` 且 `heartbeat_at < NOW() - 10min` → 回收为 `PENDING`（`attempts+1`）。
- 单实例内存保护：`workers=1` + `queue-capacity` 上限；宁可排队也不 OOM。

### 9.3 失败回滚（沿用 v1 顺序）

1. `FAILED` 时删除本次新写入的向量（`newVectorIds`）。
2. 已存在的文档恢复 `READY`（`restoreReady`），新文档删除。
3. 这些动作放在 worker 的 `catch` 里，与 v1 的 `rollbackQuietly` 完全一致，不重新发明。

---

## 10. 非功能需求

| 类别 | 指标 |
|---|---|
| 上传接口 | P95 < 300ms（只落件 + 建 job，40MB 上限内） |
| 端到端（300 chunk，云 embedding，无缓存） | P95 ≤ 90s |
| 端到端（改 3 块） | P95 ≤ 10s，`embeddingCalls ≤ 2` |
| 内存 | 单 worker 解析峰值 < 512MB；1~2G 机器 `workers=1` |
| 并发 | 同 `(namespace, docKey)` 串行（沿用 `ReentrantLock`，多实例退化由 DB 唯一约束兜底） |
| 幂等 | 同 `Idempotency-Key` 30 分钟内返回同一 job |
| 可观测 | 每阶段耗时入 `timings` + `kb_ingest_log`；失败含 stage 前缀 |
| 安全 | 原件下载需 ADMIN（历史版本）/ 当前版本（AGENT）；ACL 在两路检索都生效 |
| 兼容 | 迁移期 `?sync=true` 与旧前端可用；新表可回滚 |

---

## 11. 迁移与灰度

1. **DDL**：`003-ingestion-v2.sql` 可重复执行；先建新表，再加列（`ALTER ... ADD COLUMN IF NOT EXISTS` 在 MySQL 8 不支持，用 information_schema 判断，照抄 002 的写法）。
2. **回填**：
   - `kb_document.acl_groups = ["public"]`、`source_type = 'UPLOAD'`、`parser_name` 按 `doc_format` 推断。
   - `kb_chunk.content_hash` 由脚本按现有内容重算（或下次重建时自然补齐）。
   - `kb_document_version`：为每个现有文档补一条 v_current 历史行（无原件，`object_key = NULL`，`status='OBJECT_PRUNED'`）。
3. **灰度顺序**：
   - 第一步：只上 R1 + R3（原件留存 + 预检），主路径不变 → 风险最低。
   - 第二步：R2 异步 + `?sync=true` 兜底，观察一周。
   - 第三步：R4 缓存 + R5 版本。
   - 第四步：R6 ACL（先全 public，再逐步收紧）。
4. **回滚**：删除新接口路由即可回到 v1；新表保留不删（`kb_ingest_job` 可清空）。
5. **老文档无原件**：明确告知需要重传一次才能享受"可重解析"——这是历史债，无法自动补。

---

## 12. 里程碑与排期

| 里程碑 | 内容 | 工期 | 交付物 |
|---|---|---|---|
| M1 | R1 + R3 | 2d | 原件留存、下载接口、preview 接口 + 前端预览确认 |
| M2 | R2 + R10 | 2d | 异步 job、进度接口、审计表、前端轮询 |
| M3 | R4 | 1.5d | 缓存表、增量重建、收益埋点 |
| M4 | R5 | 1d | 版本表、版本接口、回滚 |
| M5 | R6 | 2d | ACL 字段、双路过滤、管理接口 |
| M6 | R8 + R9 | 2.5d | 感知切分、页码锚点 |
| M7 | R7 + R11 设计 | 1d | 去重、批量接口设计 |

**建议节奏**：M1 → M2 → M3 必须连续（这是"会炸"的部分），M4~M7 可穿插。

---

## 13. 验收用例（可执行剧本）

```bash
API=http://127.0.0.1:8081
KEY=lc123456

# 1) 预检：不落库
curl -s -X POST $API/v1/documents/preview -H "X-API-Key: $KEY" \
  -F "file=@接口规范.pdf" | jq '{pages,estimatedChunks,warnings}'
# 期望：返回 estimatedChunks 与 warnings；随后 GET /v1/documents 数量不变

# 2) 异步入库 + 轮询
JOB=$(curl -s -X POST $API/v1/documents -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: test-001" \
  -F "file=@接口规范.pdf" -F "docKey=ltc/design/api-spec" | jq -r .jobId)
for i in $(seq 1 60); do
  S=$(curl -s $API/v1/ingest-jobs/$JOB -H "X-API-Key: $KEY" | jq -r '.status+" "+.progress')
  echo "$S"; [ "${S%% *}" != "RUNNING" ] && break; sleep 2
done
# 期望：progress 单调递增，最终 SUCCEEDED

# 3) 幂等：同一 Idempotency-Key 再发一次
curl -s -X POST $API/v1/documents -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: test-001" -F "file=@接口规范.pdf" | jq -r .jobId
# 期望：与 $JOB 相同

# 4) 增量：改一处后重传，看 embeddingCalls
# 期望：job 详情的 embeddingCalls <= 2

# 5) 版本与回滚
DOC=$(curl -s "$API/v1/documents?namespace=ltc" -H "X-API-Key: $KEY" | jq -r '.[0].id')
curl -s $API/v1/documents/$DOC/versions -H "X-API-Key: $KEY" | jq '.[].version'
curl -s -X POST $API/v1/documents/$DOC/rollback -H "X-API-Key: $KEY" \
  -H "Content-Type: application/json" -d '{"version":1}' | jq .document.version

# 6) 去重
curl -s -X POST $API/v1/documents -H "X-API-Key: $KEY" \
  -F "file=@接口规范.pdf" -F "docKey=ltc/design/api-spec-copy"
# 期望：409 DUPLICATE_CONTENT，body 含 existingDocId

# 7) ACL（groupA 的 Key 问 groupB 的内容）
curl -s -X POST $API/v1/qa -H "X-API-Key: $KEY_A" -H "Content-Type: application/json" \
  -d '{"question":"客户等级怎么划分"}' | jq '.citations[].title'
# 期望：不含 groupB 文档

# 8) 引用带页码
curl -s -X POST $API/v1/qa -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"question":"第2页的订单表有哪些字段"}' | jq '.citations[] | {title,page}'
```

---

## 14. 风险与取舍

| 编号 | 风险 | 影响 | 应对 |
|---|---|---|---|
| R-1 | 异步化改变调用方契约 | 现有脚本/Agent 拿到 202 后以为成功 | `?sync=true` 兜底；README 明确标注；前端轮询失败自动降级 |
| R-2 | 向量缓存表膨胀（20 万块 ≈ 800MB） | MySQL 磁盘与内存压力 | `cache-max-rows` + 每小时 LRU；**备选方案 B**：不存向量，改用确定性 point id（`uuid5(chunk_hash+model)`）复用 Qdrant 中的点，缓存表只存 `hash → point_id`（几十字节/行） |
| R-3 | ACL 上线后召回骤降 | 用户以为"检索变差了" | 先全部 backfill 为 `public`，观察一周再逐步收紧；用 README §10 的 recall@5 基线对比 |
| R-4 | 按页解析增加耗时/内存 | 大 PDF 入库慢 | 单页流式 + `workers=1`；超过 300 页强制提示"建议离线 Docling" |
| R-5 | 版本历史占空间 | 原件 + Markdown 双份 | `keep=10`；同 `source_hash` 的原件只存一份（内容寻址） |
| R-6 | 异步 worker 单点 | 进程挂了任务卡住 | 心跳超时回收 + `RUNNING→PENDING` 重投；多实例靠 `SKIP LOCKED` 天然安全 |
| R-7 | 老文档没有原件 | 无法重解析 | 明确记录为已知限制；提供"重传引导"（列表页标记 `NO_SOURCE`） |

---

## 15. 待决策问题（需要老大拍板）

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| Q1 | 原件存哪里 | ① 本地磁盘（`./objects`）② MinIO 容器 | **① 先用本地磁盘**，S3 抽象留好接口；VPS 上少一个容器就少 200MB |
| Q2 | ACL 模型 | ① 按 group（`acl_groups`）② 按 api_key 白名单（文档 ↔ key 多对多） | **① group**，简单且够用；白名单在文档多时会爆表 |
| Q3 | 默认异步还是同步 | ① 默认异步（破坏兼容）② 默认同步，异步靠参数 | **① 默认异步 + `?sync=true` 兜底**，否则大文档的问题根本没解决 |
| Q4 | 历史版本保留几个 | ① 3 ② 10 ③ 全部 | **② 10**，够回溯，磁盘可控 |
| Q5 | 向量缓存放哪 | ① MySQL BLOB ② Qdrant 确定性 ID（方案 B） | **先 ①**（实现简单、可观测），块数上 10 万再切 ② |
| Q6 | 是否要 zip 批量 | 是 / 否 | 本期只出设计，不实现 |
| Q7 | 老文档无原件，是否接受重传 | 接受 / 需要兼容方案 | 接受，并在列表页显式标记 |

---

## 16. 附录

### 16.1 涉及文件清单

| 文件 | 改动类型 |
|---|---|
| `api/DocumentController.java` | 新增 preview / versions / rollback / download；upload 改异步 |
| `api/IngestJobController.java` | **新增**（进度、列表、重试、取消） |
| `service/IngestionService.java` | 拆成 `IngestWorker` + `IngestPipeline`；保留一致性顺序 |
| `service/IngestJobService.java` | **新增**（建 job、幂等、claim、心跳） |
| `chunk/Chunker.java` | 原子块识别（CODE/TABLE）、chunk_hash、page_no |
| `chunk/ChunkPiece.java` | 增加 `chunkHash`、`pageNo`、`type` 扩展 |
| `parser/DocumentParser.java` | `Parsed` 增加 `pageOffsets`、`parserName/version` |
| `parser/PdfParser.java` | 按页抽取 |
| `parser/ParserRegistry.java` | 扩展名 + magic bytes 双校验 |
| `llm/EmbeddingClient.java` | 接入缓存（查 → 调 → 回写） |
| `repo/KnowledgeRepository.java` | 新增版本、job、缓存、审计的 SQL |
| `repo/IngestJobRepository.java` | **新增** |
| `store/ObjectStore.java` | **新增**（+ `LocalObjectStore`） |
| `service/Retriever.java` | ACL 双路过滤；citation 带 page |
| `sql/migrations/003-ingestion-v2.sql` | **新增** |
| `src/main/resources/static/index.html` | 上传改"预览 → 确认 → 轮询进度"；引用卡片显示页码 |
| `README.md` | 接口示例更新（202 + 轮询）、已知限制更新 |

### 16.2 新增配置项

```yaml
rag:
  ingest:
    workers: 1                    # 3C4G 上必须为 1
    queue-capacity: 200
    poll-interval-ms: 2000
    rate-per-minute: 20           # 单 Key 每分钟入库次数
    heartbeat-timeout-seconds: 600
  storage:
    object-dir: ./objects         # 原件目录（本地存储实现）
    object-max-bytes: 41943040    # 40MB，与 MAX_UPLOAD_MB 对齐
  embedding:
    cache-enabled: true
    cache-max-rows: 200000
  version:
    keep: 10                      # 保留的历史版本数
```

### 16.3 与 README 现有承诺的差异（需同步修订）

| README 说法 | 现状 | v2 之后 |
|---|---|---|
| 「切分：标题感知 + 父子块 + **代码块保护**」 | 只保护了"`#` 不被当标题"，**实际会切断代码块** | R8 真正实现 |
| 「入库：按 docKey 幂等更新（内容 hash 不变则跳过）」 | 成立，但 hash 语义在两种入口下不一致 | 拆分 `source_hash` / `content_hash` |
| 「引用：`citations[]` 含 docId / chunkId / title / headingPath / url / score / snippet」 | 无页码 | R9 增加 `page` |
