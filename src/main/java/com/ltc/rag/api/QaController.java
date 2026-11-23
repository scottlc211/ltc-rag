package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.domain.AnswerResult;
import com.ltc.rag.repo.QaLogRepository;
import com.ltc.rag.service.QaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答接口：同步 / 流式。
 *
 * <p>同步接口直接返回 {@code answer + citations}；流式接口先发 delta，最后发一条
 * {@code citations} 事件——调用方拿到引用后可以立即校验答案，而不必等流结束再猜。
 */
@RestController
@RequestMapping("/v1")
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);
    private static final long SSE_TIMEOUT_MS = 300_000L;

    public record QaRequest(
            @NotBlank(message = "question 不能为空") String question,
            List<String> namespaces,
            Integer topK,
            String model,
            Map<String, Object> metadata
    ) {
    }

    private final QaService qaService;
    private final QaLogRepository qaLog;
    private final ExecutorService executor;

    public QaController(QaService qaService, QaLogRepository qaLog, ExecutorService ragExecutor) {
        this.qaService = qaService;
        this.qaLog = qaLog;
        this.executor = ragExecutor;
    }

    @PostMapping(value = "/qa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnswerResult qa(@Valid @RequestBody QaRequest request) {
        long start = System.currentTimeMillis();
        AnswerResult result = qaService.answer(request.question(), request.namespaces(), request.topK(), request.model());
        qaLog.record("sync", null, currentKeyId(), firstNamespace(request.namespaces()),
                request.question(), result, System.currentTimeMillis() - start);
        return result;
    }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QaRequest request,
                             jakarta.servlet.http.HttpServletResponse response) {
        // 关掉 Nginx 的缓冲，否则流式回答会攒成一坨再吐出来
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        // AuthContext 是 ThreadLocal，异步线程里拿不到，必须先摘出来
        final Long keyId = currentKeyId();
        final String namespace = firstNamespace(request.namespaces());

        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                AnswerResult result = qaService.streamAnswer(request.question(), request.namespaces(), request.topK(),
                        request.model(),
                        token -> {
                            if (closed.get()) {
                                throw new ClientGoneException();
                            }
                            send(emitter, "delta", Map.of("content", token));
                        });

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("citations", result.citations());
                meta.put("grounded", result.grounded());
                meta.put("stats", result.stats());
                send(emitter, "citations", meta);
                send(emitter, "done", Map.of("status", "SUCCEEDED"));
                emitter.complete();

                qaLog.record("stream", null, keyId, namespace,
                        request.question(), result, System.currentTimeMillis() - start);
            } catch (ClientGoneException e) {
                log.info("客户端提前断开，停止推送 question={}", abbreviate(request.question()));
                closed.set(true);
                emitter.complete();
            } catch (Exception e) {
                log.error("流式问答失败", e);
                if (!closed.get()) {
                    send(emitter, "error", Map.of(
                            "code", "STREAM_FAILED",
                            "message", e.getMessage() == null ? "流式处理失败" : e.getMessage()));
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new ClientGoneException();
        }
    }

    /** 客户端断开是正常现象，用独立异常把它和真正的故障区分开。 */
    private static final class ClientGoneException extends RuntimeException {
        private ClientGoneException() {
            super("client disconnected");
        }
    }

    private static Long currentKeyId() {
        AuthContext.Caller caller = AuthContext.caller();
        return caller == null ? null : caller.keyId();
    }

    private static String firstNamespace(List<String> namespaces) {
        return namespaces == null || namespaces.isEmpty() ? null : namespaces.get(0);
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
