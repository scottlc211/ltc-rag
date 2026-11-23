package com.ltc.rag.service;

import com.ltc.rag.config.RagProperties;
import com.ltc.rag.llm.EmbeddingClient;
import com.ltc.rag.llm.RerankClient;
import com.ltc.rag.repo.KnowledgeRepository;
import com.ltc.rag.repo.KnowledgeRepository.ChunkWithDoc;
import com.ltc.rag.repo.KnowledgeRepository.FulltextHit;
import com.ltc.rag.repo.KnowledgeRepository.ParentRef;
import com.ltc.rag.store.QdrantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 混合检索：向量召回 ⊕ BM25 召回 → RRF 融合 →（可选）rerank → 父块回填。
 *
 * <p>为什么必须混合：LTC 这类技术文档里，"错误码 E1024""/api/v2/order/create"这类
 * 精确词向量模型经常召不回来，而 FULLTEXT 一打一个准；反过来口语化的"怎么创建订单"
 * 又只有向量能理解。两路互补，不是二选一。
 *
 * <p>为什么用 RRF 而不是加权分数：向量余弦和 BM25 分数量纲完全不同，
 * 加权需要调参且换模型就失效；RRF 只看名次，稳健、免调参。
 */
@Service
public class Retriever {

    private static final Logger log = LoggerFactory.getLogger(Retriever.class);
    /** 只对融合后的前 N 条做 rerank，控制延迟与费用。 */
    private static final int RERANK_WINDOW = 20;

    public record Candidate(long chunkId, long docId, String title, String headingPath,
                            String content, String url, double score, double rerankScore) {
    }

    private final EmbeddingClient embeddings;
    private final QdrantStore qdrant;
    private final RerankClient rerank;
    private final KnowledgeRepository repo;
    private final RagProperties props;

    public Retriever(EmbeddingClient embeddings, QdrantStore qdrant, RerankClient rerank,
                     KnowledgeRepository repo, RagProperties props) {
        this.embeddings = embeddings;
        this.qdrant = qdrant;
        this.rerank = rerank;
        this.repo = repo;
        this.props = props;
    }

    public List<Candidate> retrieve(String question, List<String> namespaces, int limit) {
        long start = System.currentTimeMillis();
        List<String> ns = normalizeNamespaces(namespaces);
        RagProperties.Retrieval cfg = props.retrieval();

        // 轻量模式：没有 embedding 或没有 Qdrant 时退化为纯 BM25，链路其余部分不变
        boolean vectorMode = embeddings.isEnabled() && qdrant.isEnabled();
        List<QdrantStore.VectorHit> vectorHits = List.of();
        if (vectorMode) {
            float[] queryVector = embeddings.embed(question);
            vectorHits = qdrant.search(queryVector, ns, cfg.vectorTopK());
        }
        List<FulltextHit> fulltextHits = repo.fulltextSearch(ns, question, cfg.fulltextTopK());

        Map<String, ChunkWithDoc> byVectorId = new HashMap<>();
        for (ChunkWithDoc chunk : repo.findChunksByVectorIds(
                vectorHits.stream().map(QdrantStore.VectorHit::vectorId).toList())) {
            byVectorId.put(chunk.vectorId(), chunk);
        }

        Map<Long, Accumulator> fused = new LinkedHashMap<>();
        int rank = 0;
        for (QdrantStore.VectorHit hit : vectorHits) {
            ChunkWithDoc chunk = byVectorId.get(hit.vectorId());
            // 回表查不到 = 孤儿向量（文档已删/正在重建），直接丢弃
            if (chunk == null || hit.score() < cfg.minScore()) {
                continue;
            }
            rank++;
            fused.computeIfAbsent(chunk.chunkId(), id -> new Accumulator(chunk))
                    .addVector(hit.score(), 1.0 / (cfg.rrfK() + rank));
        }

        rank = 0;
        for (FulltextHit hit : fulltextHits) {
            rank++;
            fused.computeIfAbsent(hit.chunkId(), id -> new Accumulator(hit))
                    .addFulltext(hit.score(), 1.0 / (cfg.rrfK() + rank));
        }

        List<Accumulator> ordered = new ArrayList<>(fused.values());
        ordered.sort((a, b) -> Double.compare(b.rrf, a.rrf));
        applyRerank(question, ordered);
        List<Candidate> candidates = expandParents(ordered, limit);

        log.info("检索完成 ns={} 向量命中={} 全文命中={} 融合={} 返回={} 耗时={}ms",
                ns, vectorHits.size(), fulltextHits.size(), ordered.size(), candidates.size(),
                System.currentTimeMillis() - start);
        return candidates;
    }

    /**
     * 对前 20 条做 rerank；rerank 不可用时保持 RRF 顺序（降级不报错）。
     */
    private void applyRerank(String question, List<Accumulator> ordered) {
        if (!props.rerank().enabled() || ordered.size() < 2) {
            return;
        }
        int window = Math.min(RERANK_WINDOW, ordered.size());
        List<Accumulator> head = ordered.subList(0, window);
        List<Double> scores = rerank.rerank(question, head.stream().map(a -> a.content).toList());
        if (scores.size() != head.size()) {
            return;
        }
        for (int i = 0; i < head.size(); i++) {
            head.get(i).rerankScore = scores.get(i);
        }
        head.sort((a, b) -> Double.compare(b.rerankScore, a.rerankScore));
    }

    /**
     * 父块回填 + 去重：两个子块同属一个父块时只保留一个引用，
     * 否则同一段上下文会在 prompt 里重复出现、白烧 token。
     */
    private List<Candidate> expandParents(List<Accumulator> ordered, int limit) {
        if (ordered.isEmpty()) {
            return List.of();
        }
        List<Long> chunkIds = ordered.stream().map(a -> a.chunkId).toList();
        Map<Long, ParentRef> parents = new HashMap<>();
        for (ParentRef ref : repo.findParentsOf(chunkIds)) {
            parents.put(ref.childId(), ref);
        }

        Map<String, Candidate> deduped = new LinkedHashMap<>();
        for (Accumulator acc : ordered) {
            ParentRef parent = parents.get(acc.chunkId);
            long citationChunkId = parent == null ? acc.chunkId : parent.parentId();
            String content = parent == null ? acc.content : parent.content();
            String heading = parent == null ? acc.headingPath : parent.headingPath();
            String key = acc.docId + ":" + citationChunkId;

            if (!deduped.containsKey(key)) {
                deduped.put(key, new Candidate(citationChunkId, acc.docId, acc.title, heading,
                        content, acc.url, acc.score(), acc.rerankScore));
            }
            if (deduped.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<String> normalizeNamespaces(List<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return List.of(props.defaultNamespace());
        }
        return namespaces.stream()
                .filter(ns -> ns != null && !ns.isBlank())
                .map(ns -> ns.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** 融合期的可变累加器：同一个 chunk 可能同时被两路召回。 */
    private static final class Accumulator {
        private final long chunkId;
        private final long docId;
        private final String title;
        private final String headingPath;
        private final String content;
        private final String url;
        private double vectorScore;
        private double fulltextScore;
        private double rrf;
        private double rerankScore = Double.NaN;

        private Accumulator(ChunkWithDoc chunk) {
            this.chunkId = chunk.chunkId();
            this.docId = chunk.docId();
            this.title = chunk.title();
            this.headingPath = chunk.headingPath() == null ? "" : chunk.headingPath();
            this.content = chunk.content();
            this.url = chunk.originUrl();
        }

        private Accumulator(FulltextHit hit) {
            this.chunkId = hit.chunkId();
            this.docId = hit.docId();
            this.title = hit.title();
            this.headingPath = hit.headingPath() == null ? "" : hit.headingPath();
            this.content = hit.content();
            this.url = hit.originUrl();
        }

        private void addVector(double score, double contribution) {
            this.vectorScore = score;
            this.rrf += contribution;
        }

        private void addFulltext(double score, double contribution) {
            this.fulltextScore = score;
            this.rrf += contribution;
        }

        /** 有 rerank 分就用它排序，否则用 RRF。 */
        private double score() {
            return Double.isNaN(rerankScore) ? rrf : rerankScore;
        }
    }
}
