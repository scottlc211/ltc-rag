package com.ltc.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.common.SsrfGuard;
import com.ltc.rag.config.RagProperties;
import com.ltc.rag.domain.Citation;
import com.ltc.rag.repo.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 异步任务的提交与查询。
 *
 * <p>提交时立即返回 taskId + callbackSecret：调用方拿 secret 校验回调签名，
 * 我们不需要在系统里维护"订阅者"这一层概念，一个任务一份密钥，用完即弃。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record SubmitRequest(String question, List<String> namespaces, String callbackUrl,
                                Map<String, Object> metadata) {
    }

    public record Submitted(String taskId, String status, String callbackSecret, String pollUrl) {
    }

    public record TaskView(String taskId, String status, String question, String answer,
                           List<Citation> citations, String error, int attempts,
                           String callbackUrl, LocalDateTime createdAt, LocalDateTime finishedAt) {
    }

    private final TaskRepository repository;
    private final ObjectMapper objectMapper;
    private final RagProperties props;

    public TaskService(TaskRepository repository, ObjectMapper objectMapper, RagProperties props) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public Submitted submit(SubmitRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw ApiException.badRequest("EMPTY_QUESTION", "question 不能为空");
        }
        String callbackUrl = SsrfGuard.validateCallbackUrl(
                request.callbackUrl(), props.callback().allowPrivateIp());
        String taskId = randomHex(16);
        String callbackSecret = callbackUrl == null ? null : randomHex(24);

        String options;
        try {
            options = objectMapper.writeValueAsString(Map.of(
                    "namespaces", request.namespaces() == null ? List.of() : request.namespaces(),
                    "metadata", request.metadata() == null ? Map.of() : request.metadata()));
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_OPTIONS", "options 序列化失败");
        }

        Long apiKeyId = AuthContext.caller() == null ? null : AuthContext.caller().keyId();
        String namespace = request.namespaces() == null || request.namespaces().isEmpty()
                ? null : request.namespaces().get(0);

        repository.insert(taskId, apiKeyId, namespace, request.question().strip(), options,
                callbackUrl, callbackSecret);
        log.info("任务已受理 taskId={} callback={} question={}", taskId, callbackUrl, abbreviate(request.question()));

        return new Submitted(taskId, "PENDING", callbackSecret, "/v1/tasks/" + taskId);
    }

    public TaskView get(String taskId) {
        TaskRepository.TaskRow row = repository.findByTaskId(taskId)
                .orElseThrow(() -> ApiException.notFound("任务不存在：" + taskId));
        assertOwnership(row);
        return new TaskView(row.taskId(), row.status(), row.question(), row.answer(),
                parseCitations(row.citations()), row.error(), row.attempts(),
                row.callbackUrl(), row.createdAt(), row.finishedAt());
    }

    public void retryCallback(String taskId) {
        AuthContext.requireAdmin();
        repository.findByTaskId(taskId)
                .orElseThrow(() -> ApiException.notFound("任务不存在：" + taskId));
        if (!repository.resetCallback(taskId)) {
            throw ApiException.conflict("NO_CALLBACK", "该任务没有可重放的回调记录");
        }
        log.info("手动重放回调 taskId={}", taskId);
    }

    /** 非 ADMIN 只能看自己的任务，避免 A 的 Agent 读到 B 的问答内容。 */
    private void assertOwnership(TaskRepository.TaskRow row) {
        AuthContext.Caller caller = AuthContext.requireCaller();
        if (caller.isAdmin()) {
            return;
        }
        if (row.apiKeyId() == null || row.apiKeyId() != caller.keyId()) {
            throw ApiException.forbidden("无权查看该任务");
        }
    }

    private List<Citation> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Citation>>() {
            });
        } catch (Exception e) {
            log.warn("解析 citations 失败，返回空列表", e);
            return List.of();
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
