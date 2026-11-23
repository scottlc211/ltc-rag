package com.ltc.rag.api;

import com.ltc.rag.auth.ApiKeyService;
import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.llm.ChatClient;
import com.ltc.rag.repo.QaLogRepository;
import com.ltc.rag.repo.TaskRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维接口：签发 Agent Key、看调用量与死信。
 *
 * <p>只允许 ADMIN。Agent Key 的明文只在签发响应里出现一次。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    public record IssueKeyRequest(String name, String role, Integer quotaPerDay) {
    }

    /** 动态更新 Chat 大模型配置请求体。 */
    public record UpdateChatConfigRequest(String protocol, String baseUrl, String apiKey, String model) {
    }

    /** 探测协议或获取模型列表的请求体。 */
    public record ProbeChatRequest(String protocol, String baseUrl, String apiKey, String model) {
    }

    private final ApiKeyService apiKeyService;
    private final QaLogRepository qaLog;
    private final TaskRepository taskRepository;
    private final ChatClient chatClient;

    public AdminController(ApiKeyService apiKeyService, QaLogRepository qaLog,
                           TaskRepository taskRepository, ChatClient chatClient) {
        this.apiKeyService = apiKeyService;
        this.qaLog = qaLog;
        this.taskRepository = taskRepository;
        this.chatClient = chatClient;
    }

    @PostMapping("/keys")
    public Map<String, Object> issue(@RequestBody IssueKeyRequest request) {
        AuthContext.requireAdmin();
        String name = request.name() == null || request.name().isBlank() ? "agent" : request.name();
        String key = apiKeyService.issue(name, request.role(), request.quotaPerDay());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("role", "ADMIN".equalsIgnoreCase(request.role()) ? "ADMIN" : "AGENT");
        if (request.quotaPerDay() != null && request.quotaPerDay() > 0) {
            body.put("quotaPerDay", request.quotaPerDay());
        }
        body.put("key", key);
        body.put("notice", "明文只显示这一次，请立即保存");
        return body;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        AuthContext.requireAdmin();
        List<Map<String, Object>> daily = qaLog.dailyStats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("daily", daily);
        body.put("callbackPending", taskRepository.countOutboxByStatus("PENDING"));
        body.put("callbackFailed", taskRepository.countOutboxByStatus("FAILED"));
        return body;
    }

    /** 获取当前生效的 Chat 大模型配置（API Key 脱敏返回）。 */
    @GetMapping("/chat-config")
    public Map<String, Object> getChatConfig() {
        AuthContext.requireAdmin();
        return toChatConfigView(chatClient.currentSettings());
    }

    /** 在线热更新并持久化 Chat 大模型配置（无需重启服务）。 */
    @PutMapping("/chat-config")
    public Map<String, Object> updateChatConfig(@RequestBody UpdateChatConfigRequest req) {
        AuthContext.requireAdmin();
        ChatClient.ChatSettings updated = chatClient.updateSettings(
                req.protocol(), req.baseUrl(), req.apiKey(), req.model());
        return toChatConfigView(updated);
    }

    /** 从当前或填写的供应商接口拉取可用模型列表（类似 Magpie 的「获取模型」）。 */
    @PostMapping("/chat-config/models")
    public Map<String, Object> fetchModels(@RequestBody ProbeChatRequest req) {
        AuthContext.requireAdmin();
        List<String> models = chatClient.listModels(req.protocol(), req.baseUrl(), req.apiKey());
        return Map.of("models", models, "count", models.size());
    }

    /** 自动探测接口协议并测试模型连通性（类似 Magpie 的「检测协议」）。 */
    @PostMapping("/chat-config/probe")
    public Map<String, Object> probeProtocol(@RequestBody ProbeChatRequest req) {
        AuthContext.requireAdmin();
        return chatClient.probeProtocol(req.baseUrl(), req.apiKey(), req.model());
    }

    private static Map<String, Object> toChatConfigView(ChatClient.ChatSettings s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("protocol", s.protocol());
        view.put("baseUrl", s.baseUrl());
        view.put("model", s.model());
        view.put("apiKeyMasked", maskKey(s.apiKey()));
        view.put("configured", s.apiKey() != null && !s.apiKey().isBlank());
        return view;
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }
}
