package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.llm.ChatClient;
import com.ltc.rag.repo.KnowledgeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端启动时需要的元信息：当前是谁、有哪些知识库。
 *
 * <p>单独一个 Controller 而不是塞进 DocumentController，避免路径语义混乱
 * （/v1/me 和 /v1/namespaces 都不属于"文档资源"）。
 */
@RestController
@RequestMapping("/v1")
public class MetaController {

    private final KnowledgeRepository repository;
    private final ChatClient chatClient;

    public MetaController(KnowledgeRepository repository, ChatClient chatClient) {
        this.repository = repository;
        this.chatClient = chatClient;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthContext.Caller caller = AuthContext.requireCaller();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyId", caller.keyId());
        body.put("name", caller.name());
        body.put("role", caller.role());
        body.put("admin", caller.isAdmin());
        body.put("chatModel", chatClient.currentSettings().model());
        return body;
    }

    @GetMapping("/namespaces")
    public List<KnowledgeRepository.NamespaceStat> namespaces() {
        AuthContext.requireCaller();
        return repository.listNamespaces();
    }

    /** 新建命名空间的入参；description 可为空。 */
    public record CreateNamespaceRequest(String name, String description) {
    }

    /**
     * 新建命名空间。只有管理员能建，避免普通 Agent Key 随手造库把检索切碎。
     *
     * <p>幂等：同名重复提交不报错，只更新说明，返回 created=false。
     */
    @PostMapping("/namespaces")
    public Map<String, Object> createNamespace(@RequestBody CreateNamespaceRequest request) {
        AuthContext.requireAdmin();
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("INVALID_NAMESPACE", "命名空间名称不能为空");
        }
        boolean created;
        try {
            created = repository.createNamespace(request.name(), request.description());
        } catch (IllegalArgumentException e) {
            // 名称非法属于调用方参数问题，统一成 400，不要让前端看到 500
            throw ApiException.badRequest("INVALID_NAMESPACE", e.getMessage());
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("namespace", KnowledgeRepository.normalizeNamespaceName(request.name()));
        body.put("created", created);
        return body;
    }
}
