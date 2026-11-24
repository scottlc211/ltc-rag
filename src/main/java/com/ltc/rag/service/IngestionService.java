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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档入库（v2-Lite）：解析 → 原子切分 → 增量向量复用 → 写 Qdrant → 写 MySQL。
 *
 * <p>核心特性：
 * <ol>
 *   <li><b>严格一致性</b>：先置 INDEXING → 写新增向量 → 单事务换块并置 READY → 清理未复用旧向量，失败自动回滚；</li>
 *   <li><b>原位增量复用</b>：按分块 {@code content_hash} 复用已有文档的 Qdrant {@code vector_id}，
 *       微调/追加长文档时只算变更块，零 MySQL 向量缓存表膨胀；</li>
 *   <li><b>解析预检与可选异步</b>：支持 {@code preview} 无副作用预检，以及 {@code ?async=true} 防 Cloudflare 100s 超时。</li>
 * </ol>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_RETAINED_JOBS = 200;
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^\\s*(```|~~~)");
    private static final Pattern HEADING_LINE = Pattern.compile("(?m)^#{1,6}\\s+\\S");

    public record IngestResult(DocumentView document, boolean created, boolean changed,
                               int reusedVectors, int embeddedVectors) {
    }

    /** 文档原文视图：content 为解析后的 Markdown，旧数据可能为 null。 */
    public record DocContent(long id, String namespace, String docKey, String title, String format,
                             int version, int chunkCount, int charCount, String content,
                             LocalDateTime updatedAt) {
    }

    /** 批量重建结果。 */
    public record ReindexSummary(String namespace, int rebuilt, int skipped, int failed) {
    }

    /** 解析预检报告（不落库、不写向量）。 */
    public record PreviewWarning(String code, String message) {
    }

    public record SampleChunk(int index, String type, String headingPath, Integer page, int charCount, String preview) {
    }

    public record PreviewResult(String fileName, String format, int pages, int charCount,
                                int parentChunks, int childChunks, int tableChunks, int codeChunks,
                                List<PreviewWarning> warnings, List<SampleChunk> sampleChunks) {
    }

    /** 轻量异步入库任务视图（防大文件触发网关/Cloudflare 100s 超时）。 */
    public record IngestJobView(String jobId, String status, String fileName, String namespace, String docKey,
                                IngestResult result, String error,
                                LocalDateTime createdAt, LocalDateTime finishedAt) {
    }

    private final ParserRegistry parsers;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final QdrantStore qdrant;
    private final KnowledgeRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final RagProperties props;
    private final ExecutorService executor;

    /** 同一 (namespace, docKey) 串行重建：并发上传同一份文档不会互相删块。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** 近期异步入库任务状态缓存（内存保留最近 200 条）。 */
    private final ConcurrentHashMap<String, IngestJobView> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> jobOrder = new ConcurrentLinkedDeque<>();

    public IngestionService(ParserRegistry parsers,
                            Chunker chunker,
                            EmbeddingClient embeddings,
                            QdrantStore qdrant,
                            KnowledgeRepository repo,
                            TransactionTemplate transactionTemplate,
                            RagProperties props,
                            ExecutorService ragExecutor) {
        this.parsers = parsers;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.qdrant = qdrant;
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
        this.executor = ragExecutor;
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

    /**
     * 轻量异步入库：立即返回 jobId，后台线程执行完整解析与入库，避免大文件触发 Cloudflare 524 超时。
     */
    public IngestJobView submitAsync(String namespace, String docKey, String title, String originUrl,
                                     String fileName, byte[] bytes) {
        String ns = normalizeNamespace(namespace);
        String key = normalizeDocKey(docKey, fileName);
        String jobId = "ing_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();
        IngestJobView initial = new IngestJobView(jobId, "RUNNING", fileName, ns, key, null, null, now, null);
        recordJob(initial);

        executor.submit(() -> {
            try {
                IngestResult result = ingest(ns, key, title, originUrl, fileName, bytes);
                jobs.put(jobId, new IngestJobView(jobId, "SUCCEEDED", fileName, ns, key, result, null, now, LocalDateTime.now()));
            } catch (Exception e) {
                log.error("异步入库失败 jobId={} file={}", jobId, fileName, e);
                jobs.put(jobId, new IngestJobView(jobId, "FAILED", fileName, ns, key, null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        now, LocalDateTime.now()));
            }
        });
        return initial;
    }

    public IngestJobView getJob(String jobId) {
        IngestJobView job = jobs.get(jobId);
        if (job == null) {
            throw ApiException.notFound("入库任务不存在或已过期：" + jobId);
        }
        return job;
    }

    /**
     * 解析质量预检：仅在内存中跑 Parser + Chunker，不写数据库、不调 Embedding、不写 Qdrant。
     */
    public PreviewResult preview(String fileName, byte[] bytes) {
        DocumentParser.Parsed parsed = parsers.parse(bytes, fileName);
        String markdown = parsed.markdown();
        List<ChunkPiece> pieces = chunker.split(markdown, parsed.pageOffsets());

        int parents = 0, children = 0, tables = 0, codes = 0;
        for (ChunkPiece p : pieces) {
            switch (p.type()) {
                case ChunkPiece.PARENT -> parents++;
                case ChunkPiece.TABLE -> tables++;
                case ChunkPiece.CODE -> codes++;
                default -> children++;
            }
        }

        List<PreviewWarning> warnings = new ArrayList<>();
        if (!HEADING_LINE.matcher(markdown).find() && markdown.length() > 800) {
            warnings.add(new PreviewWarning("NO_HEADING",
                    "未识别到任何 Markdown 标题（#），长文档将退化为定长切分，建议补充章节标题提升检索精度。"));
        }
        int fenceCount = 0;
        Matcher fm = FENCE_LINE.matcher(markdown);
        while (fm.find()) {
            fenceCount++;
        }
        if (fenceCount % 2 != 0) {
            warnings.add(new PreviewWarning("UNCLOSED_FENCE",
                    "检测到奇数个代码围栏标记（```），可能存在未闭合代码块，建议检查原文。"));
        }
        if ("pdf".equals(parsed.format()) && tables == 0 && markdown.contains("  ")) {
            warnings.add(new PreviewWarning("PDF_TABLE_SUSPECTED",
                    "PDF 文本层不包含原生表格标记，若文档含复杂表格，建议用 scripts/pdf2md.ps1 离线转 Markdown 后上传。"));
        }

        List<SampleChunk> samples = pieces.stream()
                .filter(p -> !p.isParent())
                .limit(5)
                .map(p -> new SampleChunk(
                        p.index(),
                        p.type(),
                        p.headingPath(),
                        p.pageNo(),
                        p.charCount(),
                        p.content().length() <= 220 ? p.content() : p.content().substring(0, 220) + "…"))
                .toList();

        int pages = parsed.pageOffsets().isEmpty() ? 0 : parsed.pageOffsets().size();
        return new PreviewResult(fileName, parsed.format(), pages, markdown.length(),
                parents, children, tables, codes, warnings, samples);
    }

    private IngestResult doIngest(String ns, String key, String title, String originUrl,
                                  String fileName, byte[] bytes) {
        String sourceHash = Digests.sha256Hex(bytes);
        String docTitle = (title == null || title.isBlank()) ? stripExtension(fileName) : title.strip();
        Optional<DocRow> existing = repo.findDoc(ns, key);

        // 原件字节未变且状态 READY 且无需补向量 → 直接跳过解析与重建
        if (existing.isPresent()
                && (sourceHash.equals(existing.get().sourceHash()) || sourceHash.equals(existing.get().contentHash()))
                && "READY".equals(existing.get().status())
                && !needsVectorRebuild(existing.get())) {
            log.info("原件未变，跳过重建 namespace={} docKey={} version={}", ns, key, existing.get().version());
            return new IngestResult(toView(existing.get()), false, false, 0, 0);
        }

        DocumentParser.Parsed parsed = parsers.parse(bytes, fileName);
        String contentHash = Digests.sha256Hex(parsed.markdown());

        if (existing.isPresent()
                && contentHash.equals(existing.get().contentHash())
                && "READY".equals(existing.get().status())
                && !needsVectorRebuild(existing.get())) {
            log.info("解析正文未变，跳过重建 namespace={} docKey={} version={}", ns, key, existing.get().version());
            return new IngestResult(toView(existing.get()), false, false, 0, 0);
        }

        return apply(ns, key, docTitle, originUrl, contentHash, sourceHash, parsed, existing, false);
    }

    /**
     * 已解析的 Markdown → 原子切分 → 增量向量复用 → 落库。
     *
     * @param forceRebuild true 时强制重算所有分块向量（如切换 embedding 模型后 /reindex）
     */
    private IngestResult apply(String ns, String key, String docTitle, String originUrl,
                               String contentHash, String sourceHash,
                               DocumentParser.Parsed parsed, Optional<DocRow> existing, boolean forceRebuild) {
        String rawContent = parsed.markdown();
        List<ChunkPiece> pieces = chunker.split(rawContent, parsed.pageOffsets());
        List<ChunkPiece> children = pieces.stream().filter(p -> !p.isParent()).toList();
        if (children.isEmpty()) {
            throw ApiException.badRequest("EMPTY_CHUNKS", "切分后没有得到任何片段，请检查文档结构");
        }

        boolean created = existing.isEmpty();
        long docId;
        if (created) {
            docId = repo.insertDoc(ns, key, docTitle, parsed.format(), originUrl, contentHash, sourceHash, rawContent);
        } else {
            docId = existing.get().id();
            repo.markIndexing(docId);
        }

        boolean vectorMode = embeddings.isEnabled() && qdrant.isEnabled();
        String[] assignedVectorIds = new String[children.size()];
        List<String> newVectorIds = new ArrayList<>();
        Set<String> reusedVectorIds = new HashSet<>();
        boolean[] committed = {false};

        try {
            if (vectorMode) {
                Map<String, String> existingVectors = (!created && !forceRebuild)
                        ? repo.findExistingVectorsByHash(docId)
                        : Map.of();

                List<Integer> toEmbedIdx = new ArrayList<>();
                List<String> toEmbedTexts = new ArrayList<>();

                for (int i = 0; i < children.size(); i++) {
                    ChunkPiece child = children.get(i);
                    String reusable = child.contentHash() == null ? null : existingVectors.get(child.contentHash());
                    // 同一 vector_id 只能被一个新分块复用，避免两块重复内容共享同一 Qdrant pointId
                    if (reusable != null && reusedVectorIds.add(reusable)) {
                        assignedVectorIds[i] = reusable;
                    } else {
                        toEmbedIdx.add(i);
                        toEmbedTexts.add(child.content());
                    }
                }

                if (!toEmbedTexts.isEmpty()) {
                    List<float[]> vectors = embeddings.embedAll(toEmbedTexts);
                    List<QdrantStore.Point> points = new ArrayList<>(vectors.size());
                    for (int k = 0; k < vectors.size(); k++) {
                        String vectorId = UUID.randomUUID().toString();
                        int childIdx = toEmbedIdx.get(k);
                        assignedVectorIds[childIdx] = vectorId;
                        newVectorIds.add(vectorId);
                        points.add(new QdrantStore.Point(vectorId, vectors.get(k), Map.of(
                                "namespace", ns,
                                "docId", docId,
                                "docKey", key,
                                "title", docTitle)));
                    }
                    qdrant.upsert(points);
                }
            }

            List<String> staleVectorIds = transactionTemplate.execute(status -> {
                List<String> stale = repo.deleteChunks(docId);
                Map<Integer, Long> idByIndex = new HashMap<>(pieces.size() * 2);
                int[] childCursor = {0};
                for (ChunkPiece piece : pieces) {
                    Long parentId = piece.parentIndex() == null ? null : idByIndex.get(piece.parentIndex());
                    String vectorId = piece.isParent() || !vectorMode ? null : assignedVectorIds[childCursor[0]++];
                    long chunkId = repo.insertChunk(docId, piece.type(), piece.index(), parentId,
                            piece.headingPath(), piece.content(), piece.charCount(),
                            piece.contentHash(), piece.pageNo(), vectorId);
                    idByIndex.put(piece.index(), chunkId);
                }
                repo.finishDoc(docId, docTitle, parsed.format(), originUrl, contentHash, sourceHash,
                        children.size(), rawContent.length(), rawContent);
                return stale;
            });

            committed[0] = true;

            // 事务提交后，仅清理未被本次更新复用的淘汰旧向量
            List<String> toDelete = staleVectorIds == null ? List.of() : staleVectorIds.stream()
                    .filter(id -> !reusedVectorIds.contains(id))
                    .toList();
            if (!toDelete.isEmpty()) {
                try {
                    qdrant.delete(toDelete);
                } catch (RuntimeException e) {
                    log.warn("旧向量清理失败（不影响检索结果） docKey={} count={}", key, toDelete.size(), e);
                }
            }

            DocRow saved = repo.findDocById(docId)
                    .orElseThrow(() -> ApiException.internal("DOC_NOT_FOUND", "入库后读取文档失败", null));
            log.info("入库完成 namespace={} docKey={} 叶子块={} 总块={} 复用向量={} 新算向量={} 字符={}",
                    ns, key, children.size(), pieces.size(), reusedVectorIds.size(), newVectorIds.size(), rawContent.length());
            return new IngestResult(toView(saved), created, true, reusedVectorIds.size(), newVectorIds.size());
        } catch (RuntimeException e) {
            if (!committed[0]) {
                rollbackQuietly(created, docId, newVectorIds);
            } else {
                log.error("数据已提交但后续读取失败，请检查文档 id={}", docId, e);
            }
            throw e;
        }
    }

    private boolean needsVectorRebuild(DocRow row) {
        if (!(embeddings.isEnabled() && qdrant.isEnabled())) {
            return false;
        }
        return row.chunkCount() > 0 && repo.countVectors(row.id()) == 0;
    }

    public DocContent content(long docId) {
        DocRow doc = repo.findDocById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在：id=" + docId));
        String raw = repo.rawContent(docId).orElse(null);
        return new DocContent(doc.id(), doc.namespace(), doc.docKey(), doc.title(), doc.format(),
                doc.version(), doc.chunkCount(), doc.charCount(), raw, doc.updatedAt());
    }

    public IngestResult replaceContent(long docId, String markdown, String title) {
        return replaceContent(docId, markdown, title, false);
    }

    /**
     * @param force true 时即使正文没变也强制全量重算向量（切换 embedding 模型/维度后必须强制重建一次）
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
            String contentHash = Digests.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
            String docTitle = (title == null || title.isBlank()) ? current.title() : title.strip();
            if (!force && contentHash.equals(current.contentHash()) && "READY".equals(current.status())
                    && docTitle.equals(current.title()) && !needsVectorRebuild(current)) {
                log.info("正文未变，跳过重建 docId={}", docId);
                return new IngestResult(toView(current), false, false, 0, 0);
            }
            return apply(current.namespace(), current.docKey(), docTitle, current.originUrl(),
                    contentHash, contentHash,
                    new DocumentParser.Parsed(text, current.format()), Optional.of(current), force);
        } finally {
            lock.unlock();
        }
    }

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

    public IngestResult appendContent(long docId, String extra) {
        if (extra == null || extra.isBlank()) {
            throw ApiException.badRequest("EMPTY_CONTENT", "追加内容不能为空");
        }
        String base = repo.rawContent(docId)
                .orElseThrow(() -> ApiException.badRequest("NO_RAW_CONTENT",
                        "该文档没有保存原文（旧版本入库），请重新上传一次后再编辑"));
        return replaceContent(docId, base + "\n\n" + extra.strip() + "\n", null);
    }

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

    private void recordJob(IngestJobView job) {
        jobs.put(job.jobId(), job);
        jobOrder.addLast(job.jobId());
        while (jobOrder.size() > MAX_RETAINED_JOBS) {
            String oldest = jobOrder.pollFirst();
            if (oldest != null) {
                jobs.remove(oldest);
            }
        }
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
