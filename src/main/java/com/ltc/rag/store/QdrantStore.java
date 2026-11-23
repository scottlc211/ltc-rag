package com.ltc.rag.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 的极薄客户端。
 *
 * <p>不引第三方 SDK：只用 5 个 REST 接口（建集合、写入、检索、按 id 删除、探活），
 * 约 150 行就能覆盖，少一个依赖就少一处版本冲突。
 */
@Component
public class QdrantStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantStore.class);

    public record Point(String id, float[] vector, Map<String, Object> payload) {
    }

    /**
     * 向量命中。注意这里<b>不带 chunkId</b>：分块是入库后才拿到的自增 ID，
     * 由调用方拿 vectorId 回表（kb_chunk.vector_id）取权威数据，避免"向量里存了过期 ID"。
     */
    public record VectorHit(String vectorId, long docId, String namespace, String title, double score) {
    }

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String collection;
    private final int dimension;
    private final boolean enabled;
    private volatile boolean collectionReady;

    public QdrantStore(RestClient ragRestClient, ObjectMapper objectMapper, RagProperties props) {
        this.http = ragRestClient;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(props.storage().qdrantUrl());
        this.collection = props.storage().collection();
        this.dimension = props.embedding().dimension();
        this.enabled = props.storage().enabled();
        if (!enabled) {
            log.warn("轻量模式：rag.storage.enabled=false，跳过 Qdrant（只做 BM25 检索）");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 幂等建集合：已存在则跳过；维度对不上会显式报错而不是静默写脏数据。 */
    public synchronized void ensureCollection() {
        if (!enabled || collectionReady) {
            return;
        }
        try {
            JsonNode existing = objectMapper.readTree(
                    http.get().uri(collectionUrl()).retrieve().body(String.class));
            int existingSize = existing.path("result").path("config").path("params").path("vectors").path("size").asInt(-1);
            if (existingSize > 0 && existingSize != dimension) {
                throw ApiException.badRequest("QDRANT_DIMENSION_MISMATCH",
                        "集合 " + collection + " 维度为 " + existingSize + "，当前 embedding 维度为 " + dimension
                                + "。换模型必须重建集合（删 collection 或改 QDRANT_COLLECTION）。");
            }
        } catch (HttpClientErrorException.NotFound notFound) {
            createCollection();
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            throw ApiException.badGateway("QDRANT_UNAVAILABLE", "连接 Qdrant 失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw ApiException.internal("QDRANT_ERROR", "检查 Qdrant 集合失败", e);
        }
        collectionReady = true;
    }

    private void createCollection() {
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", dimension, "distance", "Cosine"));
        http.put().uri(collectionUrl()).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
        log.info("已创建 Qdrant 集合 {}，维度 {}", collection, dimension);
    }

    public void upsert(List<Point> points) {
        if (!enabled || points.isEmpty()) {
            return;
        }
        ensureCollection();
        List<Map<String, Object>> payload = new ArrayList<>(points.size());
        for (Point p : points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.id());
            item.put("vector", p.vector());
            item.put("payload", p.payload());
            payload.add(item);
        }
        http.put().uri(collectionUrl() + "/points?wait=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", payload))
                .retrieve().toBodilessEntity();
    }

    public void delete(List<String> ids) {
        if (!enabled || ids == null || ids.isEmpty()) {
            return;
        }
        http.post().uri(collectionUrl() + "/points/delete?wait=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", ids))
                .retrieve().toBodilessEntity();
    }

    /**
     * 向量检索，按 namespace 过滤（多知识库/多版本隔离靠它）。
     */
    public List<VectorHit> search(float[] vector, List<String> namespaces, int limit) {
        if (!enabled) {
            return List.of();
        }
        ensureCollection();
        Map<String, Object> filter = Map.of(
                "must", List.of(Map.of("key", "namespace", "match", Map.of("any", namespaces))));
        Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", limit,
                "with_payload", true,
                "with_vector", false,
                "filter", filter);

        String json = http.post().uri(collectionUrl() + "/points/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);

        try {
            JsonNode root = objectMapper.readTree(json);
            List<VectorHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("result")) {
                JsonNode payload = item.path("payload");
                hits.add(new VectorHit(
                        item.path("id").asText(),
                        payload.path("docId").asLong(),
                        payload.path("namespace").asText(""),
                        payload.path("title").asText(""),
                        item.path("score").asDouble()));
            }
            return hits;
        } catch (Exception e) {
            throw ApiException.internal("QDRANT_PARSE_ERROR", "解析 Qdrant 检索结果失败", e);
        }
    }

    /** 探活：健康检查接口用，失败返回 false 而不是抛异常。 */
    public boolean healthy() {
        if (!enabled) {
            return false;
        }
        try {
            http.get().uri(baseUrl + "/collections").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String collectionUrl() {
        return baseUrl + "/collections/" + collection;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
