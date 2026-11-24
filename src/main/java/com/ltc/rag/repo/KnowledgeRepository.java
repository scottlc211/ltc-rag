package com.ltc.rag.repo;

import com.ltc.rag.domain.DocumentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 文档 / 分块 / 检索的持久层。
 *
 * <p>用 JdbcTemplate 手写 SQL 而不是 ORM：表只有两张，SQL 一目了然，
 * 排查"为什么召回少了一条"时不用穿透 ORM 的缓存与懒加载。
 */
@Repository
public class KnowledgeRepository {

    public record DocRow(
            long id, String docKey, String namespace, String title, String format, String originUrl,
            String contentHash, String sourceHash, int version, String status, int chunkCount, int charCount,
            String error, LocalDateTime updatedAt) {
    }

    public record ChunkRow(long id, long docId, String headingPath, String content) {
    }

    public record FulltextHit(long chunkId, long docId, String title, String headingPath,
                              String content, String originUrl, Integer pageNo, double score) {
    }

    /** 向量 ID 回表结果：以数据库为准，Qdrant 里残留的孤儿向量会被自然丢弃。 */
    public record ChunkWithDoc(String vectorId, long chunkId, long docId, String title,
                               String headingPath, String content, String originUrl, Integer pageNo) {
    }

    /** 子块 → 父块 的上下文回填。 */
    public record ParentRef(long childId, long parentId, String headingPath, String content, Integer pageNo) {
    }

    private static final RowMapper<DocRow> DOC_MAPPER = (rs, i) -> new DocRow(
            rs.getLong("id"),
            rs.getString("doc_key"),
            rs.getString("namespace"),
            rs.getString("title"),
            rs.getString("doc_format"),
            rs.getString("origin_url"),
            rs.getString("content_hash"),
            rs.getString("source_hash"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getInt("char_count"),
            rs.getString("error"),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocRow> findDoc(String namespace, String docKey) {
        List<DocRow> rows = jdbc.query(
                """
                SELECT id, doc_key, namespace, title, doc_format, origin_url, content_hash, source_hash,
                       version, status, chunk_count, char_count, error, updated_at
                FROM kb_document WHERE namespace = ? AND doc_key = ?
                """, DOC_MAPPER, namespace, docKey);
        return rows.stream().findFirst();
    }

    public Optional<DocRow> findDocById(long id) {
        List<DocRow> rows = jdbc.query(
                """
                SELECT id, doc_key, namespace, title, doc_format, origin_url, content_hash, source_hash,
                       version, status, chunk_count, char_count, error, updated_at
                FROM kb_document WHERE id = ?
                """, DOC_MAPPER, id);
        return rows.stream().findFirst();
    }

    public long insertDoc(String namespace, String docKey, String title, String format, String originUrl,
                          String contentHash, String sourceHash, String rawContent) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO kb_document
                        (doc_key, namespace, title, doc_format, origin_url, content_hash, source_hash,
                         version, status, chunk_count, char_count, raw_content)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'INDEXING', 0, 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, docKey);
            ps.setString(2, namespace);
            ps.setString(3, title);
            ps.setString(4, format);
            ps.setString(5, originUrl);
            ps.setString(6, contentHash);
            ps.setString(7, sourceHash);
            ps.setString(8, rawContent);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入文档后未拿到自增 ID");
        }
        return key.longValue();
    }

    /** 重建期间置为 INDEXING：检索层会过滤掉它，避免读到"半新半旧"的碎片。 */
    public void markIndexing(long id) {
        jdbc.update("UPDATE kb_document SET status = 'INDEXING', error = NULL WHERE id = ?", id);
    }

    public void restoreReady(long id) {
        jdbc.update("UPDATE kb_document SET status = 'READY' WHERE id = ?", id);
    }

    public void finishDoc(long id, String title, String format, String originUrl, String contentHash,
                          String sourceHash, int chunkCount, int charCount, String rawContent) {
        jdbc.update("""
                UPDATE kb_document
                   SET title = ?, doc_format = ?, origin_url = ?, content_hash = ?, source_hash = ?,
                       version = version + 1, status = 'READY', chunk_count = ?, char_count = ?,
                       raw_content = ?, error = NULL
                 WHERE id = ?
                """, title, format, originUrl, contentHash, sourceHash, chunkCount, charCount, rawContent, id);
    }

    /** 原文（解析后的 Markdown）。旧版本入库的文档这一列为空，前端要能区分。 */
    public Optional<String> rawContent(long docId) {
        List<String> rows = jdbc.query("SELECT raw_content FROM kb_document WHERE id = ?",
                (rs, i) -> rs.getString(1), docId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 文档已有多少个分块带向量。从 BM25 模式切到向量模式时用来判断要不要强制重建。 */
    public int countVectors(long docId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE doc_id = ? AND vector_id IS NOT NULL",
                Integer.class, docId);
        return n == null ? 0 : n;
    }

    /**
     * 查询某文档当前已入库分块的 (content_hash -> vector_id) 映射，供增量更新时原位复用 Qdrant 向量。
     */
    public java.util.Map<String, String> findExistingVectorsByHash(long docId) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        jdbc.query(
                "SELECT content_hash, vector_id FROM kb_chunk WHERE doc_id = ? AND content_hash IS NOT NULL AND vector_id IS NOT NULL",
                rs -> {
                    String h = rs.getString("content_hash");
                    String v = rs.getString("vector_id");
                    if (h != null && v != null) {
                        map.putIfAbsent(h, v);
                    }
                }, docId);
        return map;
    }

    /**
     * 删除某文档的全部分块，返回被删分块的向量 ID（调用方随后去 Qdrant 清理未复用的旧向量）。
     */
    public List<String> deleteChunks(long docId) {
        List<String> vectorIds = jdbc.query(
                "SELECT vector_id FROM kb_chunk WHERE doc_id = ? AND vector_id IS NOT NULL",
                (rs, i) -> rs.getString(1), docId);
        jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);
        return vectorIds;
    }

    public long insertChunk(long docId, String chunkType, int chunkIndex, Long parentId,
                            String headingPath, String content, int charCount,
                            String contentHash, Integer pageNo, String vectorId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO kb_chunk
                        (doc_id, chunk_type, chunk_index, parent_id, heading_path, content,
                         char_count, content_hash, page_no, vector_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, docId);
            ps.setString(2, chunkType);
            ps.setInt(3, chunkIndex);
            if (parentId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, parentId);
            }
            ps.setString(5, headingPath);
            ps.setString(6, content);
            ps.setInt(7, charCount);
            ps.setString(8, contentHash);
            if (pageNo == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, pageNo);
            }
            ps.setString(10, vectorId);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入分块后未拿到自增 ID");
        }
        return key.longValue();
    }

    /**
     * 文档列表。namespace 传 "*" 表示跨全部命名空间（前端"全部"选项用），
     * 其余值按命名空间精确过滤。支持 limit + offset 翻页。
     */
    public List<DocumentView> listDocs(String namespace, int limit) {
        return listDocs(namespace, limit, 0);
    }

    public List<DocumentView> listDocs(String namespace, int limit, int offset) {
        boolean all = "*".equals(namespace);
        int safeOffset = Math.max(0, offset);
        String sql = """
                SELECT id, namespace, doc_key, title, doc_format, version, status,
                       chunk_count, char_count, origin_url, error, updated_at
                FROM kb_document
                """ + (all ? "" : " WHERE namespace = ? ") + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
        Object[] args = all ? new Object[]{limit, safeOffset} : new Object[]{namespace, limit, safeOffset};
        return jdbc.query(sql, (rs, i) -> new DocumentView(
                rs.getLong("id"),
                rs.getString("namespace"),
                rs.getString("doc_key"),
                rs.getString("title"),
                rs.getString("doc_format"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getInt("char_count"),
                rs.getString("origin_url"),
                rs.getString("error"),
                rs.getTimestamp("updated_at").toLocalDateTime()), args);
    }

    public List<DocumentView> listDocsInNamespaces(Collection<String> namespaces, int limit) {
        return listDocsInNamespaces(namespaces, limit, 0);
    }

    public List<DocumentView> listDocsInNamespaces(Collection<String> namespaces, int limit, int offset) {
        if (namespaces == null || namespaces.isEmpty()) {
            return List.of();
        }
        int safeOffset = Math.max(0, offset);
        String placeholders = String.join(",", Collections.nCopies(namespaces.size(), "?"));
        List<Object> args = new ArrayList<>(namespaces);
        args.add(limit);
        args.add(safeOffset);
        return jdbc.query("""
                SELECT id, namespace, doc_key, title, doc_format, version, status,
                       chunk_count, char_count, origin_url, error, updated_at
                FROM kb_document WHERE namespace IN (%s)
                ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?
                """.formatted(placeholders), (rs, i) -> new DocumentView(
                rs.getLong("id"),
                rs.getString("namespace"),
                rs.getString("doc_key"),
                rs.getString("title"),
                rs.getString("doc_format"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getInt("char_count"),
                rs.getString("origin_url"),
                rs.getString("error"),
                rs.getTimestamp("updated_at").toLocalDateTime()), args.toArray());
    }

    public void deleteDoc(long id) {
        jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
    }

    /**
     * BM25 召回（MySQL FULLTEXT + ngram）。
     *
     * <p>为什么一定要有它：向量检索对"精确术语/错误码/接口路径"很弱，
     * 而 LTC 这类文档里恰恰全是这类词。两路召回用 RRF 融合才稳。
     */
    public List<FulltextHit> fulltextSearch(List<String> namespaces, String query, int limit) {
        if (namespaces.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(namespaces.size(), "?"));
        String sql = """
                SELECT c.id, c.doc_id, c.heading_path, c.content, c.page_no, d.title, d.origin_url,
                       MATCH(c.content) AGAINST (?) AS score
                FROM kb_chunk c
                JOIN kb_document d ON d.id = c.doc_id
                WHERE c.chunk_type IN ('CHILD', 'CODE', 'TABLE')
                  AND d.status = 'READY'
                  AND d.namespace IN (%s)
                  AND MATCH(c.content) AGAINST (?)
                ORDER BY score DESC
                LIMIT ?
                """.formatted(placeholders);

        List<Object> args = new ArrayList<>();
        args.add(query);
        args.addAll(namespaces);
        args.add(query);
        args.add(limit);

        return jdbc.query(sql, (rs, i) -> new FulltextHit(
                rs.getLong("id"),
                rs.getLong("doc_id"),
                rs.getString("title"),
                rs.getString("heading_path"),
                rs.getString("content"),
                rs.getString("origin_url"),
                (Integer) rs.getObject("page_no"),
                rs.getDouble("score")), args.toArray());
    }

    public List<ChunkRow> findChunks(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT id, doc_id, heading_path, content FROM kb_chunk WHERE id IN (" + placeholders + ")",
                (rs, i) -> new ChunkRow(
                        rs.getLong("id"),
                        rs.getLong("doc_id"),
                        rs.getString("heading_path"),
                        rs.getString("content")),
                ids.toArray());
    }

    /** 前端下拉框用：有哪些知识库、各多少文档与分块。 */
    public record NamespaceStat(String namespace, long docs, long chunks) {
    }

    public List<NamespaceStat> listNamespaces() {
        // 注册表 ∪ 实际有文档的库：先建的空库也要出现在下拉框里，否则"先建库再上传"无从操作。
        // 文档数仍从 kb_document 实时统计，注册表只负责"这个名字存在过"。
        return jdbc.query("""
                SELECT ns.name AS ns,
                       COALESCE(d.docs, 0) AS docs,
                       COALESCE(d.chunks, 0) AS chunks
                FROM kb_namespace ns
                LEFT JOIN (
                    SELECT namespace, COUNT(*) AS docs, COALESCE(SUM(chunk_count), 0) AS chunks
                    FROM kb_document
                    WHERE status = 'READY'
                    GROUP BY namespace
                ) d ON d.namespace = ns.name
                UNION ALL
                SELECT d2.namespace AS ns, COUNT(*) AS docs, COALESCE(SUM(d2.chunk_count), 0) AS chunks
                FROM kb_document d2
                WHERE d2.status = 'READY'
                  AND NOT EXISTS (SELECT 1 FROM kb_namespace n2 WHERE n2.name = d2.namespace)
                GROUP BY d2.namespace
                ORDER BY docs DESC, ns ASC
                """, (rs, i) -> new NamespaceStat(
                rs.getString("ns"), rs.getLong("docs"), rs.getLong("chunks")));
    }

    /** 命名空间名合法性：允许字母（含中文）、数字、- _ .，长度 1~64。 */
    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,63}$");

    /**
     * 新建命名空间；同名已存在时只更新说明，不报错（幂等）。
     *
     * @return true=本次新建；false=已存在，仅更新了说明
     */
    public boolean createNamespace(String rawName, String description) {
        String name = normalizeNamespaceName(rawName);
        boolean exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_namespace WHERE name = ?", Integer.class, name) > 0;
        jdbc.update("""
                INSERT INTO kb_namespace (name, description) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """, name, description == null ? "" : description.strip());
        return !exists;
    }

    /**
     * 命名空间名规范化：去空格 + 统一小写（与入库时的处理保持一致），并校验字符合法性。
     * 小写是为了避免 "CRM" 和 "crm" 变成两个库。
     */
    public static String normalizeNamespaceName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("命名空间名称不能为空");
        }
        String name = rawName.strip().toLowerCase(Locale.ROOT);
        if (!NAMESPACE_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("命名空间名称只允许字母、数字、-、_、.，且长度不超过 64");
        }
        return name;
    }

    public void touchDocument(long docId) {
        jdbc.update("UPDATE kb_document SET updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), docId);
    }

    /**
     * 向量 ID → 分块。这里 JOIN 文档并过滤 READY：
     * 正在重建的文档不会漏出半成品，Qdrant 里的历史残留在回表时被自然丢弃。
     */
    public List<ChunkWithDoc> findChunksByVectorIds(Collection<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(vectorIds.size(), "?"));
        return jdbc.query("""
                SELECT c.vector_id, c.id, c.doc_id, c.heading_path, c.content, c.page_no, d.title, d.origin_url
                FROM kb_chunk c
                JOIN kb_document d ON d.id = c.doc_id
                WHERE d.status = 'READY' AND c.vector_id IN (%s)
                """.formatted(placeholders),
                (rs, i) -> new ChunkWithDoc(
                        rs.getString("vector_id"),
                        rs.getLong("id"),
                        rs.getLong("doc_id"),
                        rs.getString("title"),
                        rs.getString("heading_path"),
                        rs.getString("content"),
                        rs.getString("origin_url"),
                        (Integer) rs.getObject("page_no")),
                vectorIds.toArray());
    }

    public List<ParentRef> findParentsOf(Collection<Long> childIds) {
        if (childIds == null || childIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(childIds.size(), "?"));
        return jdbc.query("""
                SELECT c.id AS child_id, p.id AS parent_id, p.heading_path, p.content, p.page_no
                FROM kb_chunk c
                JOIN kb_chunk p ON p.id = c.parent_id
                WHERE c.id IN (%s)
                """.formatted(placeholders),
                (rs, i) -> new ParentRef(
                        rs.getLong("child_id"),
                        rs.getLong("parent_id"),
                        rs.getString("heading_path"),
                        rs.getString("content"),
                        (Integer) rs.getObject("page_no")),
                childIds.toArray());
    }
}
