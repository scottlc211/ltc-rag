package com.ltc.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容的 Chat 客户端：同步 + SSE 流式。
 *
 * <p>流式用 JDK HttpClient 直接读行，不引 Reactor：只有这样才拿得到
 * "每个 token 一到就回调"的行为，而且连接异常能如实抛出，不会变成静默截断。
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    public record Reply(String content, Integer promptTokens, Integer completionTokens) {
    }

    /**
     * 运行期生效的 Chat 配置快照。
     *
     * @param protocol 协议类型：openai（/v1/chat/completions）、openai-responses（/v1/responses）、
     *                 anthropic（/v1/messages）、gemini（/v1beta/models/{model}:generateContent）
     */
    public record ChatSettings(String protocol, String baseUrl, String apiKey, String model,
                               double temperature, int maxTokens) {
    }

    private final RestClient http;
    private final HttpClient streamingHttp;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final RagProperties.Chat cfg;
    private volatile ChatSettings activeSettings;

    public ChatClient(RestClient ragRestClient, ObjectMapper objectMapper, JdbcTemplate jdbc, RagProperties props) {
        this.http = ragRestClient;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.cfg = props.chat();
        this.activeSettings = new ChatSettings(
                "openai",
                stripTrailingSlash(cfg.baseUrl()),
                cfg.apiKey() == null ? "" : cfg.apiKey().strip(),
                cfg.model() == null ? "deepseek-chat" : cfg.model().strip(),
                cfg.temperature(),
                cfg.maxTokens());
        this.streamingHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 启动时自动确保 kb_config 表存在，并加载已持久化的动态 Chat 配置。
     */
    @PostConstruct
    public void loadPersistedSettings() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS kb_config (
                        config_key   VARCHAR(64)   NOT NULL,
                        config_value VARCHAR(2048) NOT NULL,
                        updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (config_key)
                    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
                    """);
            Map<String, String> map = new LinkedHashMap<>();
            jdbc.query("SELECT config_key, config_value FROM kb_config WHERE config_key LIKE 'chat.%'", rs -> {
                map.put(rs.getString(1), rs.getString(2));
            });
            if (!map.isEmpty()) {
                ChatSettings cur = this.activeSettings;
                String protocol = normalizeProtocol(map.getOrDefault("chat.protocol", cur.protocol()));
                String baseUrl = map.getOrDefault("chat.base_url", cur.baseUrl());
                String apiKey = map.getOrDefault("chat.api_key", cur.apiKey());
                String model = map.getOrDefault("chat.model", cur.model());
                this.activeSettings = new ChatSettings(
                        protocol, stripTrailingSlash(baseUrl), apiKey, model, cur.temperature(), cur.maxTokens());
                log.info("已从数据库加载自定义 Chat 配置: protocol={} baseUrl={} model={}",
                        this.activeSettings.protocol(), this.activeSettings.baseUrl(), this.activeSettings.model());
            }
        } catch (RuntimeException e) {
            log.warn("读取 kb_config 动态配置跳过（使用环境变量默认配置）: {}", e.getMessage());
        }
    }

    public ChatSettings currentSettings() {
        return activeSettings;
    }

    public boolean isConfigured() {
        ChatSettings s = activeSettings;
        return s.apiKey() != null && !s.apiKey().isBlank() && s.model() != null && !s.model().isBlank();
    }

    /**
     * 动态更新并持久化 Chat 模型配置（无需重启服务即刻生效）。
     */
    public synchronized ChatSettings updateSettings(String newProtocol, String newBaseUrl,
                                                    String newApiKey, String newModel) {
        ChatSettings cur = this.activeSettings;
        String protocol = normalizeProtocol(newProtocol == null || newProtocol.isBlank() ? cur.protocol() : newProtocol);
        String baseUrl = (newBaseUrl == null || newBaseUrl.isBlank()) ? cur.baseUrl() : stripTrailingSlash(newBaseUrl.strip());
        String apiKey = (newApiKey == null || newApiKey.isBlank() || newApiKey.contains("***")) ? cur.apiKey() : newApiKey.strip();
        String model = (newModel == null || newModel.isBlank()) ? cur.model() : newModel.strip();

        upsertConfig("chat.protocol", protocol);
        upsertConfig("chat.base_url", baseUrl);
        upsertConfig("chat.api_key", apiKey);
        upsertConfig("chat.model", model);

        this.activeSettings = new ChatSettings(protocol, baseUrl, apiKey, model, cur.temperature(), cur.maxTokens());
        log.info("已动态更新 Chat 配置: protocol={} baseUrl={} model={}", protocol, baseUrl, model);
        return this.activeSettings;
    }

    /**
     * 从供应商网关拉取可用模型列表（支持 OpenAI / Anthropic / Gemini 兼容端点）。
     */
    public List<String> listModels(String rawProtocol, String rawBaseUrl, String rawApiKey) {
        ChatSettings cur = this.activeSettings;
        String protocol = normalizeProtocol(rawProtocol == null || rawProtocol.isBlank() ? cur.protocol() : rawProtocol);
        String baseUrl = stripTrailingSlash((rawBaseUrl == null || rawBaseUrl.isBlank()) ? cur.baseUrl() : rawBaseUrl.strip());
        String apiKey = (rawApiKey == null || rawApiKey.isBlank() || rawApiKey.contains("***")) ? cur.apiKey() : rawApiKey.strip();
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.badRequest("CHAT_KEY_MISSING", "请先填写 API 密钥");
        }

        String url = "gemini".equals(protocol)
                ? geminiBase(baseUrl) + "/models?key=" + apiKey
                : baseUrl + "/models";
        try {
            var spec = http.get().uri(url);
            if ("anthropic".equals(protocol)) {
                spec = spec.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            } else if (!"gemini".equals(protocol)) {
                spec = spec.header("Authorization", "Bearer " + apiKey);
            }
            String json = spec.retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(json);
            List<String> models = new java.util.ArrayList<>();
            JsonNode list = root.has("data") ? root.path("data")
                    : (root.has("models") ? root.path("models") : root);
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String id = item.path("id").asText(item.path("name").asText(""));
                    if (id.startsWith("models/")) {
                        id = id.substring("models/".length());
                    }
                    if (!id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            return models;
        } catch (RestClientException e) {
            throw ApiException.badGateway("FETCH_MODELS_FAILED", "拉取模型列表失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw ApiException.internal("PARSE_MODELS_ERROR", "解析模型列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 自动探测该 Base URL + API Key 支持哪种协议（类似 Magpie 的「检测协议」）。
     */
    public Map<String, Object> probeProtocol(String rawBaseUrl, String rawApiKey, String testModel) {
        ChatSettings cur = this.activeSettings;
        String baseUrl = stripTrailingSlash((rawBaseUrl == null || rawBaseUrl.isBlank()) ? cur.baseUrl() : rawBaseUrl.strip());
        String apiKey = (rawApiKey == null || rawApiKey.isBlank() || rawApiKey.contains("***")) ? cur.apiKey() : rawApiKey.strip();
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.badRequest("CHAT_KEY_MISSING", "请先填写 API 密钥");
        }

        String candidateModel = (testModel != null && !testModel.isBlank()) ? testModel.strip() : cur.model();
        List<String> fetchedModels = List.of();
        for (String proto : List.of("openai", "anthropic", "gemini")) {
            try {
                fetchedModels = listModels(proto, baseUrl, apiKey);
                if (!fetchedModels.isEmpty()) {
                    if (testModel == null || testModel.isBlank()) {
                        candidateModel = fetchedModels.get(0);
                    }
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        List<String> order = List.of("openai", "openai-responses", "anthropic", "gemini");
        String lastError = "未匹配到可用协议";
        for (String proto : order) {
            try {
                ChatSettings probeSet = new ChatSettings(proto, baseUrl, apiKey, candidateModel, 0.2, 16);
                Reply reply = executeSync(probeSet, "You are a helpful assistant.", "Reply with OK", candidateModel);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("success", true);
                ok.put("protocol", proto);
                ok.put("model", candidateModel);
                ok.put("models", fetchedModels);
                ok.put("sample", reply.content());
                return ok;
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("success", false);
        fail.put("models", fetchedModels);
        fail.put("message", lastError);
        return fail;
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null) {
            return "openai";
        }
        return switch (protocol.strip().toLowerCase( java.util.Locale.ROOT)) {
            case "openai-responses", "responses" -> "openai-responses";
            case "anthropic", "claude" -> "anthropic";
            case "gemini", "google" -> "gemini";
            default -> "openai";
        };
    }

    private static String geminiBase(String baseUrl) {
        if (baseUrl.endsWith("/v1")) {
            return baseUrl.substring(0, baseUrl.length() - 3) + "/v1beta";
        }
        if (!baseUrl.contains("/v1beta")) {
            return baseUrl + "/v1beta";
        }
        return baseUrl;
    }

    private void upsertConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO kb_config (config_key, config_value) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)
                """, key, value == null ? "" : value);
    }

    public Reply complete(String system, String user) {
        return complete(system, user, null);
    }

    public Reply complete(String system, String user, String overrideModel) {
        return executeSync(activeSettings, system, user, overrideModel);
    }

    private Reply executeSync(ChatSettings s, String system, String user, String overrideModel) {
        if (s.apiKey() == null || s.apiKey().isBlank()) {
            throw ApiException.badRequest("CHAT_KEY_MISSING", "CHAT_API_KEY 未配置，请在「密钥与统计 -> 大模型配置」中配置");
        }
        String targetModel = resolveModel(s, overrideModel);
        String proto = normalizeProtocol(s.protocol());

        try {
            return switch (proto) {
                case "openai-responses" -> completeOpenAiResponses(s, system, user, targetModel);
                case "anthropic" -> completeAnthropic(s, system, user, targetModel);
                case "gemini" -> completeGemini(s, system, user, targetModel);
                default -> completeOpenAi(s, system, user, targetModel);
            };
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            throw ApiException.badGateway("LLM_HTTP_ERROR", "调用大模型失败（" + proto + "）：" + e.getMessage(), e);
        } catch (Exception e) {
            throw ApiException.internal("LLM_PARSE_ERROR", "解析大模型响应失败：" + e.getMessage(), e);
        }
    }

    private Reply completeOpenAi(ChatSettings s, String system, String user, String model) throws Exception {
        String endpoint = stripTrailingSlash(s.baseUrl()) + "/chat/completions";
        Map<String, Object> body = baseBody(s, system, user, model);
        body.put("stream", false);
        String json = http.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + s.apiKey())
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(json);
        JsonNode message = root.path("choices").path(0).path("message").path("content");
        if (message.isMissingNode()) {
            throw ApiException.badGateway("LLM_BAD_RESPONSE", "模型返回体缺少 choices[0].message.content", null);
        }
        JsonNode usage = root.path("usage");
        return new Reply(message.asText(""),
                usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }

    private Reply completeOpenAiResponses(ChatSettings s, String system, String user, String model) throws Exception {
        String endpoint = stripTrailingSlash(s.baseUrl()) + "/responses";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", system);
        body.put("input", user);
        body.put("temperature", s.temperature());
        body.put("max_output_tokens", s.maxTokens());
        String json = http.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + s.apiKey())
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(json);
        String text = root.path("output_text").asText("");
        if (text.isBlank()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode out : root.path("output")) {
                for (JsonNode c : out.path("content")) {
                    if (c.has("text")) {
                        sb.append(c.path("text").asText(""));
                    }
                }
            }
            text = sb.toString();
        }
        JsonNode usage = root.path("usage");
        return new Reply(text,
                usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null,
                usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null);
    }

    private Reply completeAnthropic(ChatSettings s, String system, String user, String model) throws Exception {
        String endpoint = stripTrailingSlash(s.baseUrl()) + "/messages";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", s.maxTokens());
        body.put("temperature", s.temperature());
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        String json = http.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", s.apiKey())
                .header("Authorization", "Bearer " + s.apiKey())
                .header("anthropic-version", "2023-06-01")
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(json);
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText("text")) && block.has("text")) {
                sb.append(block.path("text").asText(""));
            }
        }
        JsonNode usage = root.path("usage");
        return new Reply(sb.toString(),
                usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null,
                usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null);
    }

    private Reply completeGemini(ChatSettings s, String system, String user, String model) throws Exception {
        String endpoint = geminiBase(stripTrailingSlash(s.baseUrl())) + "/models/" + model + ":generateContent?key=" + s.apiKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", Map.of("temperature", s.temperature(), "maxOutputTokens", s.maxTokens()));
        String json = http.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(json);
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            sb.append(part.path("text").asText(""));
        }
        JsonNode usage = root.path("usageMetadata");
        return new Reply(sb.toString(),
                usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null,
                usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null);
    }

    public Reply stream(String system, String user, Consumer<String> onDelta) {
        return stream(system, user, null, onDelta);
    }

    /**
     * 流式生成：OpenAI 兼容协议走原生 SSE 流；其它协议（Anthropic / OpenAI Responses / Gemini）
     * 自动走同步请求并回调给前端，保证所有协议在聊天界面都能无缝工作。
     */
    public Reply stream(String system, String user, String overrideModel, Consumer<String> onDelta) {
        ChatSettings s = activeSettings;
        String proto = normalizeProtocol(s.protocol());
        if (!"openai".equals(proto)) {
            Reply reply = executeSync(s, system, user, overrideModel);
            if (reply.content() != null && !reply.content().isEmpty()) {
                onDelta.accept(reply.content());
            }
            return reply;
        }
        String endpoint = stripTrailingSlash(s.baseUrl()) + "/chat/completions";
        Map<String, Object> body = baseBody(s, system, user, overrideModel);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .header("Authorization", "Bearer " + s.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw ApiException.internal("LLM_REQUEST_ERROR", "构造流式请求失败", e);
        }

        HttpResponse<Stream<String>> response;
        try {
            response = streamingHttp.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            throw ApiException.badGateway("LLM_STREAM_ERROR", "建立流式连接失败：" + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorBody(response.body());
            throw ApiException.badGateway("LLM_HTTP_ERROR",
                    "流式调用返回 " + response.statusCode() + "：" + detail, null);
        }

        StringBuilder answer = new StringBuilder();
        Integer[] promptTokens = {null};
        Integer[] completionTokens = {null};

        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode chunk = objectMapper.readTree(payload);
                if (chunk.hasNonNull("error")) {
                    throw ApiException.badGateway("LLM_STREAM_ERROR",
                            chunk.path("error").path("message").asText("模型返回错误"), null);
                }
                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    if (usage.has("prompt_tokens")) {
                        promptTokens[0] = usage.get("prompt_tokens").asInt();
                    }
                    if (usage.has("completion_tokens")) {
                        completionTokens[0] = usage.get("completion_tokens").asInt();
                    }
                }
                JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                if (delta.isTextual()) {
                    String token = delta.asText();
                    // 思考型模型（GLM/DeepSeek-R 等）首包常常是空串（只有 reasoning_content），
                    // 空 token 不往下推，否则前端会先闪一个空块
                    if (token.isEmpty()) {
                        continue;
                    }
                    answer.append(token);
                    onDelta.accept(token);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // 客户端断开（onDelta 抛出的运行时异常）必须原样透传，不能被包装成"流式中断"
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway("LLM_STREAM_ERROR", "读取流式响应中断：" + e.getMessage(), e);
        }

        if (answer.isEmpty()) {
            log.warn("流式响应结束但没有任何内容，请检查网关是否兼容 OpenAI 流式协议");
        }
        return new Reply(answer.toString(), promptTokens[0], completionTokens[0]);
    }

    private static String resolveModel(ChatSettings s, String overrideModel) {
        return (overrideModel != null && !overrideModel.isBlank() && !"ltc-rag".equalsIgnoreCase(overrideModel.strip()))
                ? overrideModel.strip()
                : s.model();
    }

    private Map<String, Object> baseBody(ChatSettings s, String system, String user, String overrideModel) {
        if (s.apiKey() == null || s.apiKey().isBlank()) {
            throw ApiException.badRequest("CHAT_KEY_MISSING", "CHAT_API_KEY 未配置，请在环境变量或「密钥与统计 -> 模型设置」中配置");
        }
        String targetModel = resolveModel(s, overrideModel);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", targetModel);
        body.put("temperature", s.temperature());
        body.put("max_tokens", s.maxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        return body;
    }

    private static String readErrorBody(Stream<String> body) {
        try (Stream<String> stream = body) {
            String text = stream.limit(20).collect(Collectors.joining("\n"));
            return text.length() > 800 ? text.substring(0, 800) : text;
        } catch (Exception e) {
            return "(无法读取错误响应体)";
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
