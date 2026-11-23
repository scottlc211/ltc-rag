package com.ltc.rag.api;

import com.ltc.rag.config.RagProperties;
import com.ltc.rag.llm.ChatClient;
import com.ltc.rag.parser.ParserRegistry;
import com.ltc.rag.store.QdrantStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（不需要 API Key）。
 *
 * <p>返回的是"依赖是否就绪"而不是"进程还活着"：
 * Qdrant 连不上、embedding key 没配，都应该在探活里暴露出来。
 */
@RestController
public class HealthController {

    private final QdrantStore qdrant;
    private final RagProperties props;
    private final ParserRegistry parsers;
    private final ChatClient chatClient;

    public HealthController(QdrantStore qdrant, RagProperties props, ParserRegistry parsers, ChatClient chatClient) {
        this.qdrant = qdrant;
        this.props = props;
        this.parsers = parsers;
        this.chatClient = chatClient;
    }

    @GetMapping("/v1/health")
    public Map<String, Object> health() {
        // "配了开关"不等于"能用"：Key 没填或 Qdrant 掉了，实际都只会走 BM25
        boolean embeddingReady = props.embedding().enabled() && configured(props.embedding().apiKey());
        String vectorStore = qdrant.isEnabled() ? (qdrant.healthy() ? "UP" : "DOWN") : "DISABLED";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", OffsetDateTime.now().toString());
        body.put("vectorStore", vectorStore);
        body.put("embeddingConfigured", embeddingReady);
        body.put("mode", embeddingReady && "UP".equals(vectorStore) ? "hybrid" : "fulltext-only");
        body.put("chatConfigured", chatClient.isConfigured());
        body.put("chatModel", chatClient.currentSettings().model());
        body.put("rerankEnabled", props.rerank().enabled() && configured(props.rerank().apiKey()));
        body.put("supportedFormats", parsers.supportedExtensions());
        return body;
    }

    private static boolean configured(String key) {
        return key != null && !key.isBlank();
    }
}
