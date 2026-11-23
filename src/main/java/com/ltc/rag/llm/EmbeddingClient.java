package com.ltc.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.config.AppConfig;
import com.ltc.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 Embedding 客户端。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>失败要响</b>：不静默跳过（原参考项目就是跳过后只打 warn，库里静默缺块）；</li>
 *   <li><b>维度要校验</b>：维度与 Qdrant 集合不一致时，宁可报错也不能写进去。</li>
 * </ul>
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final RagProperties.Embedding cfg;
    private final String endpoint;

    public EmbeddingClient(ObjectMapper objectMapper, RagProperties props) {
        this.cfg = props.embedding();
        this.http = AppConfig.buildRestClient(cfg.timeoutSeconds());
        this.objectMapper = objectMapper;
        this.endpoint = stripTrailingSlash(cfg.baseUrl()) + "/embeddings";
        if (!cfg.enabled()) {
            log.warn("轻量模式：rag.embedding.enabled=false，检索只走 BM25（不调用 embedding 服务）");
        } else if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            log.warn("EMBEDDING_API_KEY 未配置，入库和检索会在调用时报错");
        }
    }

    public boolean isEnabled() {
        // 只开了开关却没给 Key 等于没配：降级成纯 BM25，而不是等到入库时炸掉
        return cfg.enabled() && cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * 批量向量化，保持与入参顺序严格一致（按 index 字段回排，不依赖服务端返回顺序）。
     */
    public List<float[]> embedAll(List<String> texts) {
        if (!cfg.enabled()) {
            throw ApiException.internal("EMBEDDING_DISABLED",
                    "embedding 已关闭（rag.embedding.enabled=false），不应调用本方法", null);
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, cfg.batchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            vectors.addAll(callWithRetry(texts.subList(start, end)));
        }
        return vectors;
    }

    private List<float[]> callWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callOnce(batch);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Embedding 第 {}/{} 次失败: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleepQuietly(300L * attempt);
            }
        }
        throw last == null
                ? ApiException.badGateway("EMBEDDING_FAILED", "Embedding 调用失败", null)
                : last;
    }

    private List<float[]> callOnce(List<String> batch) {
        Map<String, Object> body = Map.of("model", cfg.model(), "input", batch);
        String json;
        try {
            json = http.post().uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw ApiException.badGateway("EMBEDDING_HTTP_ERROR", "Embedding 请求失败：" + e.getMessage(), e);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw ApiException.badGateway("EMBEDDING_BAD_RESPONSE",
                        "Embedding 返回体缺少 data：" + abbreviate(json), null);
            }
            float[][] ordered = new float[batch.size()][];
            int cursor = 0;
            for (JsonNode item : data) {
                int index = item.path("index").asInt(cursor++);
                JsonNode embedding = item.path("embedding");
                if (index < 0 || index >= ordered.length || !embedding.isArray()) {
                    continue;
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                if (vector.length != cfg.dimension()) {
                    throw ApiException.badRequest("EMBEDDING_DIMENSION_MISMATCH",
                            "模型 " + cfg.model() + " 实际输出 " + vector.length
                                    + " 维，配置为 " + cfg.dimension() + " 维。请同步 EMBEDDING_DIMENSION 与 Qdrant 集合。");
                }
                ordered[index] = vector;
            }
            List<float[]> result = new ArrayList<>(ordered.length);
            for (float[] vector : ordered) {
                if (vector == null) {
                    throw ApiException.badGateway("EMBEDDING_INCOMPLETE", "Embedding 返回条数与入参不一致", null);
                }
                result.add(vector);
            }
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.internal("EMBEDDING_PARSE_ERROR", "解析 Embedding 响应失败", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : text.substring(0, Math.min(200, text.length()));
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
