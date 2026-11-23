package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.llm.ChatClient;
import com.ltc.rag.repo.KnowledgeRepository;
import org.springframework.web.bind.annotation.GetMapping;
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
}
