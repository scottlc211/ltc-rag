package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.domain.AnswerResult;
import com.ltc.rag.repo.QaLogRepository;
import com.ltc.rag.service.QaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * OpenAI 兼容入口。
 *
 * <p>存在的意义：绝大多数 Agent 框架/LangChain/各类客户端默认就会说
 * {@code /v1/chat/completions}，有这个接口就是"改个 base_url 就能接"，
 * 比让对方适配我们的自定义协议现实得多。
 *
 * <p>引用信息放在响应的 {@code ltc} 扩展字段里，不破坏标准结构。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final QaService qaService;
    private final QaLogRepository qaLog;
    private final ExecutorService executor;

    public OpenAiController(QaService qaService, QaLogRepository qaLog, ExecutorService ragExecutor) {
        this.qaService = qaService;
        this.qaLog = qaLog;
        this.executor = ragExecutor;
    }

    @PostMapping(value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object completions(@RequestBody Map<String, Object> body,
                              jakarta.servlet.http.HttpServletResponse response) {
        String question = extractQuestion(body);
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest("EMPTY_QUESTION", "messages 中缺少 user 消息");
        }
        List<String> namespaces = extractNamespaces(body);
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        String model = String.valueOf(body.getOrDefault("model", "ltc-rag"));

        if (stream) {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            return streamCompletion(question, namespaces, model);
        }

        long start = System.currentTimeMillis();
        AnswerResult result = qaService.answer(question, namespaces, null, model);
        Long keyId = AuthContext.caller() == null ? null : AuthContext.caller().keyId();
        qaLog.record("openai", null, keyId, namespaces.isEmpty() ? null : namespaces.get(0),
                question, result, System.currentTimeMillis() - start);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", Map.of("role", "assistant", "content", result.answer()));
        choice.put("finish_reason", "stop");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        payload.put("object", "chat.completion");
        payload.put("created", Instant.now().getEpochSecond());
        payload.put("model", model);
        payload.put("choices", List.of(choice));
        payload.put("usage", usage(result));
        payload.put("ltc", Map.of("citations", result.citations(), "grounded", result.grounded()));
        return payload;
    }

    private SseEmitter streamCompletion(String question, List<String> namespaces, String model) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long created = Instant.now().getEpochSecond();
        final Long keyId = AuthContext.caller() == null ? null : AuthContext.caller().keyId();
        final String namespace = namespaces.isEmpty() ? null : namespaces.get(0);

        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                AnswerResult result = qaService.streamAnswer(question, namespaces, null, model,
                        token -> sendChunk(emitter, id, created, model, token));
                sendChunk(emitter, id, created, model, null);
                try {
                    emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                } catch (IOException e) {
                    // 客户端已断开，正常结束即可
                }
                emitter.complete();
                qaLog.record("openai", null, keyId, namespace,
                        question, result, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("OpenAI 兼容流式失败", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendChunk(SseEmitter emitter, String id, long created, String model, String content) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (content != null) {
            delta.put("content", content);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", content == null ? "stop" : null);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        try {
            emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("客户端已断开", e);
        }
    }

    private static Map<String, Object> usage(AnswerResult result) {
        int prompt = result.stats().promptTokens() == null ? 0 : result.stats().promptTokens();
        int completion = result.stats().completionTokens() == null ? 0 : result.stats().completionTokens();
        return Map.of("prompt_tokens", prompt, "completion_tokens", completion,
                "total_tokens", prompt + completion);
    }

    @SuppressWarnings("unchecked")
    private static String extractQuestion(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list)) {
            return null;
        }
        String question = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            if (!"user".equals(String.valueOf(message.get("role")))) {
                continue;
            }
            Object content = message.get("content");
            if (content instanceof String text) {
                question = text;
            } else if (content instanceof List<?> parts) {
                // 兼容多模态写法：[{"type":"text","text":"..."}]
                StringBuilder sb = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap && partMap.get("text") != null) {
                        sb.append(partMap.get("text"));
                    }
                }
                question = sb.toString();
            }
        }
        return question;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractNamespaces(Map<String, Object> body) {
        Object extra = body.get("ltc");
        if (!(extra instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object ns = map.get("namespaces");
        if (!(ns instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
