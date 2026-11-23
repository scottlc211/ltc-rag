# LTC RAG · 项目知识库

面向 **LTC 项目文档**的自建知识库：**混合检索（向量 + BM25）→ 结构化引用 → 带回调的 Agent API**。

设计目标只有一条：**让别的 Agent 敢调用、能校验、可重试**。所以它不是一个"上传文档聊天的 Demo"，
而是一个"知识服务"：每条答案都能回溯到 `doc_id / chunk_id / 段落路径 / 原文链接`。

---

## 1. 与参考项目（rag-study-helper）的差异

参考项目链路完整、注释扎实，适合学习；但它的定位是"人用的问答页"，与"Agent 调用的知识服务"差在四处，
本项目正是对着这四处设计的：

| 维度 | rag-study-helper | 本项目 |
|---|---|---|
| 鉴权 | 仅 IP 令牌桶 | API Key（SHA256 存储）+ 角色（ADMIN/AGENT） |
| 对外接口 | 只有 SSE `/api/chat` | 同步 / SSE / **异步任务 + 回调** / **OpenAI 兼容** |
| 引用 | 靠 prompt 让模型自己写 `[来源:文件]` | 检索结果直接产出 `citations[]`，`[n]` 与数组下标严格对应 |
| 一致性 | 先写向量再写库，失败即孤儿数据 | 先置 `INDEXING` → 写向量 → 事务换块 → 置 `READY`，失败自动回滚 |
| 解析 | PDFBox 裸抽，`.xls` 用 `XSSFWorkbook`（必抛异常） | `WorkbookFactory` 兼容 xls/xlsx；PDF 无文本层明确报错并提示 OCR |
| 切分 | 固定 512 token | 标题感知 + 父子块 + 代码块保护 |
| 技术栈 | JDK 8 / Spring Boot 2.6（已 EOL） | JDK 17 / Spring Boot 3.3 |

---

## 2. 能力清单

- **界面**：单文件 SPA（问答 / 文档库 / 异步任务 / 密钥统计），后端直接托管，零 npm 依赖
- **入库**：`md / txt / docx / xlsx / xls / pdf / html`，按 `docKey` 幂等更新（内容 hash 不变则跳过重建）
- **检索**：Qdrant 向量 top-30 ⊕ MySQL FULLTEXT(ngram) top-30 → RRF 融合 → 可选 rerank → 父块回填
- **问答**：同步 JSON / SSE 流式 / 异步任务 + Webhook / OpenAI 兼容 `chat/completions`
- **引用**：`citations[]` 含 `docId / chunkId / title / headingPath / url / score / snippet`
- **回调**：HMAC-SHA256 签名、指数退避重试 5 次、死信可查可重放、`taskId` 幂等
- **审计**：`kb_qa_log` 记录耗时、命中数、token 消耗

---

## 3. 架构

```
                        ┌──────────────────── 鉴权 (ApiKeyFilter) ────────────────────┐
 Agent / 调用方 ────────▶│ /v1/qa  /v1/qa/stream  /v1/tasks  /v1/chat/completions      │
                        └───────────────┬─────────────────────────────┬───────────────┘
                                        │                             │
                              ┌─────────▼─────────┐          ┌────────▼────────┐
                              │  Retriever        │          │ ChatClient      │
                              │  向量⊕BM25 → RRF   │          │ (OpenAI 兼容)    │
                              │  → rerank → 父块   │          └────────┬────────┘
                              └────┬─────────┬────┘                   │
                                   │         │                        ▼
                        ┌──────────▼──┐  ┌───▼──────────┐     你的网关 / DeepSeek
                        │  Qdrant     │  │ MySQL 8      │
                        │  (向量)      │  │ 文档/块/任务/ │
                        └─────────────┘  │ 回调/日志     │
                                         └──────────────┘
                                                ▲
                        ┌───────────────────────┴──────────────────────┐
                        │ TaskWorker(轮询抢占) → CallbackDispatcher(投递) │
                        └──────────────────────────────────────────────┘
```

**为什么不用 MQ / Redis**：3C4G 上每省一个中间件就多一份稳定性和内存。任务队列用
`kb_task + FOR UPDATE SKIP LOCKED`，回调用 `kb_callback_outbox`，语义够用且重启不丢。

---

## 4. 3C4G 资源预算

| 组件 | 内存 | 说明 |
|---|---|---|
| App (JVM) | ~700MB | `-XX:MaxRAMPercentage=65` 自动按容器内存算 |
| Qdrant | ~250MB | 10 万级向量以内 |
| MySQL（复用已有则省） | ~400MB | `innodb-buffer-pool-size=256M` |
| 系统 + 余量 | ~1.5GB | 解析大 PDF 时会短暂上涨 |

> **解析重活不要在 3C4G 上做**：扫描件先用 `ocrmypdf`，复杂表格先用 Docling 转 Markdown，
> 再走 `POST /v1/documents/text`。服务器只做"切分 + 向量化 + 检索"。

---

## 5. 快速开始

```bash
cp .env.example .env      # 填 EMBEDDING_API_KEY / CHAT_API_KEY
docker compose up -d --build
docker compose logs -f app
```

首次启动会自动生成一个 ADMIN Key 并打印在日志里（只显示一次）：

```
已生成初始 ADMIN API Key（只显示这一次，请立即保存）： ltc_xxxxxxxx...
```

本机自用嫌这串太长？可以改成自己好记的（库里只存 SHA256，校验时现算哈希比对，
所以改完**不用重启**，立刻生效）：

```powershell
.\scripts\set-admin-key.ps1 -Key "lc123456"
# 更新 id/name/key_prefix/role/status，然后自动调 /v1/me 验证
```

登录地址：`http://127.0.0.1:8081/#k=lc123456`（hash 会被立即从地址栏抹掉）。
浏览器里存着旧 Key 的话，点左下角「清除本机 Key」重新登录即可。

> 这串等价于管理员密码。8 位纯字母数字只适合本机，对公网暴露前请换成 20 位以上的随机串。

健康检查（不需要 Key）：

```bash
curl -s localhost:8080/v1/health | jq
# {"status":"UP","vectorStore":"UP","embeddingConfigured":true,"chatConfigured":true,...}
```

### 可视化界面

浏览器打开 **http://服务器:8080/** 即可。前端是后端直接托管的单文件 SPA（`src/main/resources/static/index.html`），
**不需要 Node、不需要 npm 构建、不依赖任何外部 CDN**，内网服务器也能用。

| 页面 | 能做什么 |
|---|---|
| 知识问答 | 流式回答（逐字出）、引用卡片（点正文里的 `[n]` 跳到对应片段）、按命名空间过滤、调引用条数 |
| 文档库 | 拖拽多文件上传、按命名空间筛选、看分块数/版本/更新时间、删除（连带清向量） |
| 异步任务 | 提交带 `callbackUrl` 的任务、轮询状态、拿到 `callbackSecret`、查看结果与引用 |
| 密钥与统计 | 签发 AGENT/ADMIN Key（明文只显示一次）、近 30 天调用量、回调队列积压 |

Key 只存在浏览器 localStorage；用 **AGENT** Key 登录时，上传与删除入口会自动隐藏（后端本来也会 403）。

> 前面挂 Nginx 时，SSE 必须关缓冲，否则流式回答会"攒一大坨再吐"：
> `proxy_buffering off; proxy_read_timeout 300s;`（服务端已返回 `X-Accel-Buffering: no`）。

### 本地运行（没有 Docker 也能跑）

只需要 **JDK 17+** 和 **MySQL 8**，不需要 Qdrant、不需要 embedding 服务：

```powershell
# 1. 构建（本机没装 Maven 时，脚本会用 ~/.m2 里缓存的 Maven）
powershell -File .\scripts\local-build.ps1

# 2. 启动：自带一个独立 MySQL（3307，数据在 .localdb\，不碰系统里的 MySQL80）
powershell -File .\scripts\local-up.ps1

# 3. 浏览器打开 http://127.0.0.1:8081/   停止用 scripts\local-down.ps1
```

模型与数据库配置写在项目根目录的 `.env`（已被 `.gitignore` 忽略，脚本会自动读取并注入环境变量）：

```ini
CHAT_BASE_URL=http://127.0.0.1:3425/v1     # 任意 OpenAI 兼容网关
CHAT_API_KEY=magpie
CHAT_MODEL=trae-cn/glm-5.3-flash           # 思考型模型也可以，reasoning 内容会被丢弃

MYSQL_HOST=127.0.0.1
MYSQL_PORT=3307
MYSQL_DB=ltc_rag
MYSQL_USER=ltc
MYSQL_PASSWORD=ltc-local
```

首次使用需要初始化一次本地库：

```powershell
mysqld --initialize-insecure --basedir=D:\MySQL\Server8.0 --datadir=.\\.localdb\\data
# 然后建库授权（脚本不做这件事，避免误伤已有实例）：
#   CREATE DATABASE ltc_rag ...; CREATE USER 'ltc'@'%' ...; GRANT ALL ON ltc_rag.* TO 'ltc'@'%';
#   mysql ... ltc_rag < sql\schema.sql
```

**轻量模式**（`EMBEDDING_ENABLED=false` + `QDRANT_ENABLED=false`）的行为：

| 环节 | 差异 |
|---|---|
| 入库 | 只写 MySQL 分块与全文索引，`vector_id` 留空，不产生向量 |
| 检索 | 只走 BM25（中文 ngram）。接口名、错误码、专有名词照样打得准，语义泛化弱于向量 |
| 引用 | 不受影响，`citations[]` 依旧完整 |
| 升级 | 拿到 embedding Key 后把两个开关改回 `true`，重新入库即自动重建向量 |

> 适用场景：内网演示、没有 embedding 额度的环境、排查"到底是检索问题还是模型问题"。


本地开发（需本机 MySQL + Qdrant）：

```bash
mvn spring-boot:run
```

---

## 6. 文档入库

`docKey` 是**幂等键**：同一个 `docKey` 再传一次 = 版本更新（旧分块与旧向量自动清理）。
不传则用文件名，**改名就会变成两份文档**，生产环境请显式传。

```bash
# 1) 上传文件（md/docx/xlsx/pdf/html）
curl -X POST localhost:8080/v1/documents \
  -H "X-API-Key: $ADMIN_KEY" \
  -F "file=@ltc-接口规范.docx" \
  -F "docKey=ltc/design/api-spec" \
  -F "namespace=ltc" \
  -F "title=LTC 接口规范" \
  -F "url=https://git.internal/ltc/docs/api-spec"

# 2) 直接提交 Markdown（推荐：先把 PDF 在本地转好）
curl -X POST localhost:8080/v1/documents/text \
  -H "X-API-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"docKey":"ltc/ops/deploy","title":"部署手册","content":"# 部署\n...","url":"https://..."}'

# 3) 列表 / 删除
curl -s "localhost:8080/v1/documents?namespace=ltc" -H "X-API-Key: $ADMIN_KEY"
curl -X DELETE localhost:8080/v1/documents/12 -H "X-API-Key: $ADMIN_KEY"

# 4) 查看原文 / 在线编辑（改完自动重建索引，版本号 +1）
curl -s localhost:8080/v1/documents/12/content -H "X-API-Key: $ADMIN_KEY"
curl -X PUT localhost:8080/v1/documents/12/content \
  -H "X-API-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"content":"# 新正文\n..."}'

# 5) 只在末尾追加（不用回传全文，适合 Agent 写变更记录 / FAQ）
curl -X POST localhost:8080/v1/documents/12/append \
  -H "X-API-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"content":"## 变更记录\n2026-11-13：新增库存校验规则。\n"}'
```

> 编辑依赖 `kb_document.raw_content`（解析后的 Markdown 原文）。
> 老库升级要先执行 `sql/migrations/002-raw-content.sql`；升级前入库的文档没有原文，
> 接口会返回 `content: null`，用同一个 docKey 重新上传一次即可启用编辑。

返回：

```json
{
  "document": {"id":12,"docKey":"ltc/design/api-spec","version":2,"status":"READY","chunkCount":37},
  "created":false,
  "changed":true
}
```

**扫描件**会返回 `400 PDF_NO_TEXT_LAYER`，这是刻意的：静默入库一份抽不出文字的 PDF，
比报错更糟。

### 扫描件 / 图片型 PDF：先用 Docling 转 Markdown

服务器只有 3C4G、没有 GPU，不在服务器上跑重解析。正确做法是开发机转好再入库：

```powershell
# 一次性安装（约 1.5GB，含 torch；模型首次转换时再下）
powershell -File .\scripts\setup-docling.ps1

# 转换（默认带 OCR，中文质量不错；有文本层的 PDF 可加 -NoOcr 更快）
powershell -File .\scripts\pdf2md.ps1 .\docs\接口规范.pdf

# 转完直接入库，省一次手工上传
powershell -File .\scripts\pdf2md.ps1 .\docs\接口规范.pdf `
  -ApiKey $ADMIN_KEY -DocKey "ltc/design/api-spec" -Title "接口规范"
```

实测：一份**纯图片、无文本层**的中文 PDF，转出来的 Markdown 正文基本无误，
只有标题阅读顺序偶尔会偏；单页 CPU 约 30 秒（含首次模型下载，之后 3~10 秒）。

> OCR 语言代码有坑：Docling 的 rapidocr(torch) 后端用 `iso:zh`，
> tesseract/onnx 后端才是 `ch_sim`。脚本默认 `iso:zh`，纯英文用 `iso:en` 会快不少。

### 直接上传图片（截图 / 扫描件）

前端文档库和 `POST /v1/documents` 都支持 `png / jpg / jpeg / bmp / webp / tif / tiff`：

```bash
curl -X POST localhost:8081/v1/documents \
  -H "X-API-Key: $ADMIN_KEY" \
  -F "file=@客户等级.png" \
  -F "docKey=ltc/crm/customer-level" \
  -F "title=客户等级与授信规则"
# {"id":8,"format":"image","chunkCount":1,"charCount":182,"status":"READY"}   约 2 秒
```

服务端把图片发给本地 OCR 服务（`scripts/embed_server.py` 的 `POST /v1/ocr`，
和 embedding 复用同一个进程，不额外部署），识别结果按坐标聚成行列还原成 Markdown 表格
再走正常切块流程——所以截图里的表被切块后，每一行仍然带着表头。

前提：`OCR_ENABLED=true`（默认）且 `scripts/embed-up.ps1` 起的服务在跑。
关掉 OCR 时上传图片会返回 `OCR_DISABLED`，提示先用 `scripts/to-md.ps1` 在本地转换。

> 大批量历史扫描件建议还是离线转换：服务器上跑 OCR 会占 300~400MB 内存，
> 3C4G 上适合"偶尔传一张截图"，不适合一次性灌几千页。

### 从 BM25 切到向量检索（混合模式）

1. 填 `EMBEDDING_API_KEY`（硅基流动的 `BAAI/bge-m3`，1024 维，免费），保持 `EMBEDDING_ENABLED=true`；
2. 起 Qdrant：`scripts\local-up.ps1` 会自动拉起，前提是 `D:\qdrant\qdrant.exe` 存在（`-QdrantHome` 可改）；
3. 重建历史文档（关键，否则老文档没有向量）：
   ```bash
   curl -X POST "localhost:8081/v1/documents/reindex?namespace=ltc" -H "X-API-Key: $ADMIN_KEY"
   # {"namespace":"ltc","rebuilt":3,"skipped":1,"failed":0}
   ```
   `skipped` 是没有保存原文的老文档，重新上传一次即可；
4. `/v1/health` 的 `mode` 会从 `fulltext-only` 变成 `hybrid`。

> 换 embedding 模型时注意维度：`bge-m3` / `bge-large-zh-v1.5` 是 1024，
> `bge-small-zh` 是 512。维度改了必须同步 `EMBEDDING_DIMENSION` 并重新 `reindex`。

---

## 7. 问答

```bash
# 同步
curl -X POST localhost:8080/v1/qa \
  -H "X-API-Key: $AGENT_KEY" -H "Content-Type: application/json" \
  -d '{"question":"创建订单接口的必填字段有哪些？","namespaces":["ltc"],"topK":6}'
```

```json
{
  "answer": "创建订单接口 POST /api/v2/order/create 的必填字段为 user_id、sku_id、quantity [1]。",
  "citations": [
    {"index":1,"docId":12,"chunkId":318,"title":"LTC 接口规范",
     "headingPath":"接口规范 > 订单 > 创建订单","url":"https://git.internal/ltc/docs/api-spec",
     "score":0.83,"snippet":"接口规范 > 订单 > 创建订单 调用 POST /api/v2/order/create…"}
  ],
  "grounded": true,
  "stats": {"hits":6,"retrievalMs":180,"llmMs":1400,"promptTokens":2103,"completionTokens":96}
}
```

流式（SSE，事件顺序 `delta* → citations → done`）：

```bash
curl -N -X POST localhost:8080/v1/qa/stream \
  -H "X-API-Key: $AGENT_KEY" -H "Content-Type: application/json" \
  -d '{"question":"部署步骤是什么？"}'
```

OpenAI 兼容（大多数 Agent 框架改个 base_url 即可）：

```bash
curl -X POST localhost:8080/v1/chat/completions \
  -H "X-API-Key: $AGENT_KEY" -H "Content-Type: application/json" \
  -d '{"model":"ltc-rag","messages":[{"role":"user","content":"部署步骤是什么？"}],"stream":false}'
```

引用的额外信息在 `ltc` 字段里（`citations` / `grounded`），不破坏 OpenAI 标准结构。

---

## 8. 异步 + 回调

```bash
curl -X POST localhost:8080/v1/tasks \
  -H "X-API-Key: $AGENT_KEY" -H "Content-Type: application/json" \
  -d '{
        "question": "订单超时的重试策略是什么？",
        "namespaces": ["ltc"],
        "callbackUrl": "https://your-agent.internal/hooks/ltc-rag",
        "metadata": {"traceId": "abc-123"}
      }'
```

```json
{"taskId":"9f2c…","status":"PENDING","callbackSecret":"6b1e…","pollUrl":"/v1/tasks/9f2c…"}
```

`callbackSecret` 只返回一次，用于校验签名。轮询兜底：`GET /v1/tasks/{taskId}`。

### 回调请求

```http
POST https://your-agent.internal/hooks/ltc-rag
Content-Type: application/json
X-LTC-Task-Id: 9f2c…
X-LTC-Signature: t=1794473732,v1=<hmac_sha256(secret, "t.body")>
```

```json
{
  "event": "qa.completed",
  "taskId": "9f2c…",
  "status": "SUCCEEDED",
  "question": "订单超时的重试策略是什么？",
  "answer": "…[1]",
  "citations": [{"index":1,"docId":12,"chunkId":345,"title":"…","headingPath":"…","url":"…","score":0.79,"snippet":"…"}],
  "grounded": true,
  "stats": {"hits":6,"retrievalMs":210,"llmMs":1600,"promptTokens":2088,"completionTokens":120},
  "metadata": {"traceId": "abc-123"},
  "finishedAt": "2026-11-12T17:20:11.123"
}
```

### 验签（Python）

```python
import hashlib, hmac, time

def verify(secret: str, signature_header: str, raw_body: bytes) -> None:
    parts = dict(item.split("=", 1) for item in signature_header.split(","))
    timestamp, provided = parts["t"], parts["v1"]
    if abs(time.time() - int(timestamp)) > 300:
        raise ValueError("回调时间戳超出允许范围（防重放）")
    expected = hmac.new(secret.encode(), f"{timestamp}.".encode() + raw_body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, provided):
        raise ValueError("签名不匹配")
```

### 重试与死信

| 次数 | 间隔 |
|---|---|
| 1 | 10s |
| 2 | 30s |
| 3 | 90s |
| 4 | 270s |
| 5 | 进入死信（`status=FAILED`） |

- 只要返回 **2xx** 即视为送达；其余（含超时）都算失败
- 死信重放：`POST /v1/tasks/{taskId}/callback/retry`（ADMIN）
- 语义是 **至少一次**，请用 `taskId` 做幂等去重

---

## 9. 鉴权与运维

```bash
# 签发 Agent Key（ADMIN）
curl -X POST localhost:8080/v1/admin/keys \
  -H "X-API-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"name":"build-agent","role":"AGENT"}'

# 调用量与死信
curl -s localhost:8080/v1/admin/stats -H "X-API-Key: $ADMIN_KEY"

# 前端启动时用的两个元信息接口
curl -s localhost:8080/v1/me -H "X-API-Key: $AGENT_KEY"
curl -s localhost:8080/v1/namespaces -H "X-API-Key: $AGENT_KEY"
```

- Key 只存 SHA256，无法反查明文；吊销直接改 `kb_api_key.status='DISABLED'`
- `rag.auth.enabled=false` 仅限本机调试，会把调用方当作 ADMIN

---

## 10. 质量基线（务必做）

没有基线就没有优化方向。建议入库后先准备 30 条真实问题，人工标注"应命中的文档/章节"，再跑：

- `recall@5`：应命中文档是否出现在 citations 前 5
- 引用命中率：`[n]` 对应的 snippet 是否真的支撑该结论
- P95 延迟：检索 + 生成分别计时（`stats` 里已分好）

指标不达标时按顺序调：切分粒度 → 召回 topK → rerank 开关 → prompt。

---

## 11. 已知限制

1. **PDF 只抽文本层**：PDFBox 不做版面分析，双栏与复杂表格会失真；扫描件直接返回 `400 PDF_NO_TEXT_LAYER`（宁可报错，也不静默入库一份抽不出文字的 PDF）。
   **PDF 里的图片永远抽不出来**（架构图、截图、图表里的数字都会丢），要覆盖这类内容，三选一：
   - **离线 Docling / MinerU** 把 PDF 转成 Markdown（带 OCR、表格与版面识别），再走 `/v1/documents/text` —— 推荐，服务器零负担；仓库里已封装好 `scripts/setup-docling.ps1` + `scripts/pdf2md.ps1`；
   - `ocrmypdf` + Tesseract（`chi_sim`）给扫描件补文本层后上传，适合纯扫描件；
   - 用支持视觉的模型（网关里的 GLM 系列就能读图）把关键页转写成 Markdown，质量好但慢且贵，只适合少量关键页。
2. **raw_content 会额外占空间**：在线编辑要求保存一份原文，等于正文存两遍（分块 + 原文）。纯检索部署可以接受，文档量上到几万篇时要评估。
3. **无 GPU**：所有向量化/重排都走外部 API，网络抖动会体现在入库耗时上（已做 3 次重试）。
4. **无 rerank 时**：检索质量依赖 RRF，精确术语场景仍可用，但语义排序会弱于 bge-reranker。
5. **单集合多 namespace**：靠 payload 过滤隔离，适合几十万级；再大建议拆集合。
6. **未做对话历史**：面向 Agent 的单轮/带上下文提问（问题里自带上下文）效果更好；多轮会话需要调用方自己拼 history。
