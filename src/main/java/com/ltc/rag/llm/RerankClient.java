package com.ltc.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.config.AppConfig;
import com.ltc.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank（交叉编码器）调用。
 *
 * <p>失败一律"降级放行"：rerank 只是排序优化，挂了不该让整个问答失败，
 * 用 RRF 的顺序继续跑即可。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final RagProperties.Rerank cfg;
    private final String endpoint;

    public RerankClient(ObjectMapper objectMapper, RagProperties props) {
        this.cfg = props.rerank();
        this.http = AppConfig.buildRestClient(cfg.timeoutSeconds());
        this.objectMapper = objectMapper;
        this.endpoint = stripTrailingSlash(cfg.baseUrl()) + "/rerank";
    }

    /**
     * @return 与入参等长的相关性分数；任何异常都返回空列表，由调用方回退
     */
    public List<Double> rerank(String query, List<String> documents) {
        if (!cfg.enabled() || documents.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "query", query,
                "documents", documents,
                "top_n", documents.size(),
                "return_documents", false);
        try {
            String json = http.post().uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(json).path("results");
            double[] scores = new double[documents.size()];
            java.util.Arrays.fill(scores, -1);
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < scores.length) {
                    scores[index] = item.path("relevance_score").asDouble();
                }
            }
            List<Double> aligned = new ArrayList<>(scores.length);
            for (double score : scores) {
                if (score < 0) {
                    // 少一条就整体回退，避免"部分重排"导致顺序错乱
                    return List.of();
                }
                aligned.add(score);
            }
            return aligned;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退到 RRF 排序：{}", e.getMessage());
            return List.of();
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
