package com.ltc.rag.service;

import com.ltc.rag.chunk.ChunkPiece;
import com.ltc.rag.chunk.Chunker;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.common.Digests;
import com.ltc.rag.config.RagProperties;
import com.ltc.rag.domain.DocumentView;
import com.ltc.rag.llm.EmbeddingClient;
import com.ltc.rag.parser.DocumentParser;
import com.ltc.rag.parser.ParserRegistry;
import com.ltc.rag.repo.KnowledgeRepository;
import com.ltc.rag.repo.KnowledgeRepository.DocRow;
import com.ltc.rag.store.QdrantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文档入库：解析 → 切分 → 向量化 → 写 Qdrant → 写 MySQL。
 *
 * <p>一致性策略（这是参考项目最大的坑，这里正面解决）：
 * <ol>
 *   <li>文档行先以 INDEXING 落库（检索层过滤掉它，读不到半成品）；</li>
 *   <li>向量写入 Qdrant 后再在一个事务里"删旧块 + 插新块 + 置 READY"；</li>
 *   <li>任何一步失败：删掉刚写的向量，已有文档恢复 READY（旧数据完好），新文档直接删除。</li>
 * </ol>
 * 代价是失败时会浪费一次 embedding 调用，换来的是"库里永远不会出现孤儿向量/半截文档"。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public record IngestResult(DocumentView document, boolean created, boolean changed) {
    }

    /** 文档原文视图：content 为解析后的 Markdown，旧数据可能为 null。 */
    public record DocContent(long id, String namespace, String docKey, String title, String format,
                             int version, int chunkCount, int charCount, String content,
                             LocalDateTime updatedAt) {
    }

    private final ParserRegistry parsers;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final QdrantStore qdrant;
    private final KnowledgeRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final RagProperties props;

    /** 同一 (namespace, docKey) 串行重建：并发上传同一份文档不会互相删块。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public IngestionService(ParserRegistry parsers,
                            Chunker chunker,
                            EmbeddingClient embeddings,
                            QdrantStore qdrant,
                            KnowledgeRepository repo,
                            TransactionTemplate transactionTemplate,
                            RagProperties props) {
        this.parsers = parsers;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.qdrant = qdrant;
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
    }

    public IngestResult ingest(String namespace, String docKey, String title, String originUrl,
                               String fileName, byte[] bytes) {
        String ns = normalizeNamespace(namespace);
        String key = normalizeDocKey(docKey, fileName);
        String lockKey = ns + "|" + key;

        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return doIngest(ns, key, title, originUrl, fileName, bytes);
        } finally {
            lock.unlock();
        }
    }

    private IngestResult doIngest(String ns, String key, String title, String originUrl,
                                  String fileName, byte[] bytes) {
        String hash = Digests.sha256Hex(bytes);
        String docTitle = (title == null || title.isBlank()) ? stripExtension(fileName) : title.strip();
        Optional<DocRow> existing = repo.findDoc(ns, key);

        if (existing.isPresent()
                && hash.equals(existing.get().contentHash())
                && "READY".equals(existing.get().status())
                && !needsVectorRebuild(existing.get())) {
            log.info("内容未变，跳过重建 namespace={} docKey={} version={}", ns, key, existing.get().version());
            return new IngestResult(toView(existing.get()), false, false);
        }

        DocumentParser.Parsed parsed = parsers.parse(bytes, fileName);
        return apply(ns, key, docTitle, originUrl, hash, parsed, existing);
    }

    /**
     * 已解析的 Markdown → 切分 → 向量 → 落库。
     *
     * <p>上传和"在线编辑后重建"共用这一条路径，唯一差别是 parsed 从哪来：
     * 前者由解析器产出，后者就是用户改过的 Markdown（格式标签沿用原文档，不让 pdf 变 md）。
     */
    private IngestResult apply(String ns, String key, String docTitle, String originUrl, String hash,
                               DocumentParser.Parsed parsed, Optional<DocRow> existing) {
        String rawContent = parsed.markdown();
        List<ChunkPiece> pieces = chunker.split(rawContent);
        List<ChunkPiece> children = pieces.stream().filter(p -> !p.isParent()).toList();
        if (children.isEmpty()) {
            throw ApiException.badRequest("EMPTY_CHUNKS", "切分后没有得到任何片段，请检查文档结构");
        }

        boolean created = existing.isEmpty();
        long docId;
        if (created) {
            docId = repo.insertDoc(ns, key, docTitle, parsed.format(), originUrl, hash, rawContent);
        } else {
            docId = existing.get().id();
            repo.markIndexing(docId);
        }

        List<String> newVectorIds = new ArrayList<>(children.size());
        // 轻量模式（无 embedding / 无 Qdrant）下只入库分块与全文索引，vector_id 留空
        boolean vectorMode = embeddings.isEnabled() && qdrant.isEnabled();
        // 事务提交后就不能再回滚向量了，否则会留下"分块指向已删除向量"的坏数据
        boolean[] committed = {false};
        try {
            if (vectorMode) {
                List<float[]> vectors = embeddings.embedAll(children.stream().map(ChunkPiece::content).toList());
                List<QdrantStore.Point> points = new ArrayList<>(children.size());
                for (int i = 0; i < children.size(); i++) {
                    String vectorId = UUID.randomUUID().toString();
                    newVectorIds.add(vectorId);
                    points.add(new QdrantStore.Point(vectorId, vectors.get(i), Map.of(
                            "namespace", ns,
                            "docId", docId,
                            "docKey", key,
                            "title", docTitle)));
                }
                qdrant.upsert(points);
            }

            List<String> staleVectorIds = transactionTemplate.execute(status -> {
                // 先删旧块（拿到旧向量 ID），再插新块，最后置 READY —— 同一个事务
                List<String> stale = repo.deleteChunks(docId);
                Map<Integer, Long> idByIndex = new HashMap<>(pieces.size() * 2);
                int[] childCursor = {0};
                for (ChunkPiece piece : pieces) {
                    Long parentId = piece.parentIndex() == null ? null : idByIndex.get(piece.parentIndex());
                    String vectorId = piece.isParent() || !vectorMode ? null : newVectorIds.get(childCursor[0]++);
                    long chunkId = repo.insertChunk(docId, piece.type(), piece.index(), parentId,
                            piece.headingPath(), piece.content(), piece.charCount(), vectorId);
                    idByIndex.put(piece.index(), chunkId);
                }
                repo.finishDoc(docId, docTitle, parsed.format(), originUrl, hash,
                        children.size(), rawContent.length(), rawContent);
                return stale;
            });

            List<String> stale = staleVectorIds == null ? List.of() : staleVectorIds;
            committed[0] = true;

            // 事务提交后再删旧向量：即使删失败也只是留下垃圾，不会伤到新数据
            try {
                qdrant.delete(stale);
            } catch (RuntimeException e) {
                log.warn("旧向量清理失败（不影响检索结果） docKey={} count={}", key, stale.size(), e);
            }

            DocRow saved = repo.findDocById(docId)
                    .orElseThrow(() -> ApiException.internal("DOC_NOT_FOUND", "入库后读取文档失败", null));
            log.info("入库完成 namespace={} docKey={} 新块={} 总块={} 字符={}",
                    ns, key, children.size(), pieces.size(), rawContent.length());
            return new IngestResult(toView(saved), created, true);
        } catch (RuntimeException e) {
            if (!committed[0]) {
                rollbackQuietly(created, docId, newVectorIds);
            } else {
                log.error("数据已提交但后续读取失败，请检查文档 id={}", docId, e);
            }
            throw e;
        }
    }

    /**
     * 从 BM25 模式切到向量模式后，老文档的分块一个向量都没有。
     * 内容哈希没变，正常会被"跳过重建"，所以这里单独识别一次。
     */
    private boolean needsVectorRebuild(DocRow row) {
        if (!(embeddings.isEnabled() && qdrant.isEnabled())) {
            return false;
        }
        return row.chunkCount() > 0 && repo.countVectors(row.id()) == 0;
    }

    /** 文档原文（解析后的 Markdown）。旧版本入库的文档没有这一列内容，返回 null。 */
    public DocContent content(long docId) {
        DocRow doc = repo.findDocById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在：id=" + docId));
        String raw = repo.rawContent(docId).orElse(null);
        return new DocContent(doc.id(), doc.namespace(), doc.docKey(), doc.title(), doc.format(),
                doc.version(), doc.chunkCount(), doc.charCount(), raw, doc.updatedAt());
    }

    /**
     * 用新正文替换整篇文档并重建索引（在线编辑 / 追加都走这里）。
     *
     * <p>与上传同一套一致性保证：先置 INDEXING → 写向量 → 单事务换块 → 置 READY。
     * 内容没变则直接返回，不会白白 bump 版本号。
     */
    public IngestResult replaceContent(long docId, String markdown, String title) {
        return replaceContent(docId, markdown, title, false);
    }

    /**
     * @param force true 时即使正文没变也重建（切换 embedding 模型/维度后必须强制重建一次）
     */
    public IngestResult replaceContent(long docId, String markdown, String title, boolean force) {
        if (markdown == null || markdown.isBlank()) {
            throw ApiException.badRequest("EMPTY_CONTENT", "content 不能为空");
        }
        DocRow doc = repo.findDocById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在：id=" + docId));
        String lockKey = doc.namespace() + "|" + doc.docKey();
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            DocRow current = repo.findDocById(docId)
                    .orElseThrow(() -> ApiException.notFound("文档不存在：id=" + docId));
            String text = markdown.replace("\r\n", "\n");
            String hash = Digests.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
            String docTitle = (title == null || title.isBlank()) ? current.title() : title.strip();
            if (!force && hash.equals(current.contentHash()) && "READY".equals(current.status())
                    && docTitle.equals(current.title()) && !needsVectorRebuild(current)) {
                log.info("正文未变，跳过重建 docId={}", docId);
                return new IngestResult(toView(current), false, false);
            }
            return apply(current.namespace(), current.docKey(), docTitle, current.originUrl(), hash,
                    new DocumentParser.Parsed(text, current.format()), Optional.of(current));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把某个 namespace 下所有文档按已保存的原文重建一遍索引。
     *
     * <p>典型场景：刚配好 embedding（或换了模型/维度），需要把历史文档补上向量。
     * 没有原文的老文档会被跳过并计数，提示重新上传。
     */
    public ReindexSummary reindexAll(String namespace) {
        String ns = normalizeNamespace(namespace);
        List<DocumentView> docs = repo.listDocs(ns, 500);
        int ok = 0, skipped = 0, failed = 0;
        for (DocumentView doc : docs) {
            String raw = repo.rawContent(doc.id()).orElse(null);
            if (raw == null || raw.isBlank()) {
                skipped++;
                continue;
            }
            try {
                replaceContent(doc.id(), raw, null, true);
                ok++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("重建失败 docId={} docKey={}", doc.id(), doc.docKey(), e);
            }
        }
        log.info("批量重建完成 namespace={} 成功={} 跳过(无原文)={} 失败={}", ns, ok, skipped, failed);
        return new ReindexSummary(ns, ok, skipped, failed);
    }

    /** 批量重建结果。 */
    public record ReindexSummary(String namespace, int rebuilt, int skipped, int failed) {
    }

    /** 在原文末尾追加一段（等价于"编辑后保存"，但只传增量，方便 Agent 追加日志/FAQ）。 */
    public IngestResult appendContent(long docId, String extra) {
        if (extra == null || extra.isBlank()) {
            throw ApiException.badRequest("EMPTY_CONTENT", "追加内容不能为空");
        }
        String base = repo.rawContent(docId)
                .orElseThrow(() -> ApiException.badRequest("NO_RAW_CONTENT",
                        "该文档没有保存原文（旧版本入库），请重新上传一次后再编辑"));
        return replaceContent(docId, base + "\n\n" + extra.strip() + "\n", null);
    }


    /**
     * 删除文档及其全部向量。
     *
     * @return 被删除的向量条数（便于调用方确认清理范围）
     */
    public int delete(long docId) {
        DocRow doc = repo.findDocById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在：id=" + docId));
        List<String> vectorIds = repo.deleteChunks(docId);
        repo.deleteDoc(docId);
        qdrant.delete(vectorIds);
        log.info("已删除文档 id={} docKey={} 向量数={}", docId, doc.docKey(), vectorIds.size());
        return vectorIds.size();
    }

    public List<DocumentView> list(String namespace, int limit) {
        return repo.listDocs(normalizeNamespace(namespace), Math.max(1, Math.min(limit, 500)));
    }

    private void rollbackQuietly(boolean created, long docId, List<String> newVectorIds) {
        try {
            qdrant.delete(newVectorIds);
        } catch (RuntimeException e) {
            log.error("回滚向量失败，请人工清理 Qdrant 中的孤儿向量 docId={} ids={}", docId, newVectorIds, e);
        }
        try {
            if (created) {
                repo.deleteDoc(docId);
            } else {
                // 更新失败：保留旧数据，把状态恢复成 READY
                repo.restoreReady(docId);
            }
        } catch (RuntimeException e) {
            log.error("回滚文档状态失败 docId={}", docId, e);
        }
    }

    private String normalizeNamespace(String namespace) {
        return (namespace == null || namespace.isBlank())
                ? props.defaultNamespace()
                : namespace.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * docKey 缺省用文件名（去扩展名）。它是"同一份文档的稳定身份"，
     * 上传新版本时务必传入相同的 docKey，否则会被当成新文档。
     */
    private static String normalizeDocKey(String docKey, String fileName) {
        String key = (docKey == null || docKey.isBlank()) ? stripExtension(fileName) : docKey;
        key = key.strip();
        if (key.length() > 191) {
            key = key.substring(0, 191);
        }
        return key;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "untitled";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static DocumentView toView(DocRow row) {
        return new DocumentView(row.id(), row.namespace(), row.docKey(), row.title(), row.format(),
                row.version(), row.status(), row.chunkCount(), row.charCount(), row.originUrl(),
                row.error(), row.updatedAt());
    }
}
