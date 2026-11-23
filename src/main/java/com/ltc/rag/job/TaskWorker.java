package com.ltc.rag.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.config.RagProperties;
import com.ltc.rag.domain.AnswerResult;
import com.ltc.rag.repo.QaLogRepository;
import com.ltc.rag.repo.TaskRepository;
import com.ltc.rag.service.QaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 任务执行器：轮询 → 抢占 → 执行 → 落库 → 投递回调。
 *
 * <p>用"数据库表 + SKIP LOCKED"而不是内存队列：进程被 OOM kill 或重启时，
 * PENDING 任务还在表里，下次轮询会接着跑，不会静默丢单。
 */
@Component
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final TaskRepository repository;
    private final QaService qaService;
    private final QaLogRepository qaLog;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor;

    public TaskWorker(TaskRepository repository, QaService qaService, QaLogRepository qaLog,
                      ObjectMapper objectMapper, TransactionTemplate transactionTemplate,
                      ExecutorService ragExecutor) {
        this.repository = repository;
        this.qaService = qaService;
        this.qaLog = qaLog;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.executor = ragExecutor;
    }

    /** 单次轮询最多连续抢占的任务数，匹配工作线程池容量，避免积压任务每 1.5s 只消化一条。 */
    private static final int MAX_CLAIMS_PER_POLL = 4;
    /** RUNNING 超过 10 分钟视为僵尸任务（进程重启或极端卡死），最大重试 2 次。 */
    private static final int STALE_RUNNING_MINUTES = 10;
    private static final int MAX_TASK_ATTEMPTS = 2;

    @Scheduled(fixedDelayString = "1500")
    public void poll() {
        for (int i = 0; i < MAX_CLAIMS_PER_POLL; i++) {
            Long id;
            try {
                id = transactionTemplate.execute(status -> repository.claimNext().orElse(null));
            } catch (RuntimeException e) {
                log.error("抢占任务失败（可能是数据库抖动）", e);
                return;
            }
            if (id == null) {
                return;
            }
            long taskId = id;
            try {
                executor.submit(() -> process(taskId));
            } catch (RejectedExecutionException e) {
                log.warn("执行器已满，任务标记失败 taskId={}", taskId);
                repository.markFailed(taskId, "执行器繁忙，请重新提交");
                return;
            }
        }
    }

    /** 每分钟巡检一次超时未结束的僵尸 RUNNING 任务。 */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "15000")
    public void recoverStaleTasks() {
        try {
            int recovered = repository.recoverStaleRunning(
                    LocalDateTime.now().minusMinutes(STALE_RUNNING_MINUTES), MAX_TASK_ATTEMPTS);
            if (recovered > 0) {
                log.warn("已回收超时 RUNNING 僵尸任务 count={}", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("巡检超时任务失败", e);
        }
    }

    private void process(long id) {
        TaskRepository.TaskRow task = repository.findById(id).orElse(null);
        if (task == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Options options = parseOptions(task.options());
            AnswerResult result = qaService.answer(task.question(), options.namespaces());
            String citationsJson = objectMapper.writeValueAsString(result.citations());

            repository.markSucceeded(id, result.answer(), citationsJson);
            qaLog.record("async", task.taskId(), task.apiKeyId(), task.namespace(),
                    task.question(), result, System.currentTimeMillis() - start);

            if (task.callbackUrl() != null && !task.callbackUrl().isBlank()) {
                String payload = buildPayload("qa.completed", task, result, options.metadata());
                repository.enqueueCallback(task.taskId(), task.callbackUrl(), payload);
            }
            log.info("任务完成 taskId={} 耗时={}ms 引用={} 条", task.taskId(),
                    System.currentTimeMillis() - start, result.citations().size());
        } catch (Exception e) {
            log.error("任务失败 taskId={}", task.taskId(), e);
            repository.markFailed(id, abbreviate(e.getMessage()));
            if (task.callbackUrl() != null && !task.callbackUrl().isBlank()) {
                try {
                    String payload = buildFailurePayload(task, e);
                    repository.enqueueCallback(task.taskId(), task.callbackUrl(), payload);
                } catch (Exception enqueueError) {
                    log.warn("失败回调入队失败 taskId={}", task.taskId(), enqueueError);
                }
            }
        }
    }

    private record Options(List<String> namespaces, Map<String, Object> metadata) {
    }

    private Options parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return new Options(List.of(), Map.of());
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> namespaces = new ArrayList<>();
            node.path("namespaces").forEach(n -> namespaces.add(n.asText()));
            Map<String, Object> metadata = node.has("metadata")
                    ? objectMapper.convertValue(node.get("metadata"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            })
                    : Map.of();
            return new Options(namespaces, metadata);
        } catch (Exception e) {
            log.warn("解析任务 options 失败，按默认处理", e);
            return new Options(List.of(), Map.of());
        }
    }

    private String buildPayload(String event, TaskRepository.TaskRow task, AnswerResult result,
                                Map<String, Object> metadata) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("taskId", task.taskId());
        payload.put("status", "SUCCEEDED");
        payload.put("question", task.question());
        payload.put("answer", result.answer());
        payload.put("citations", result.citations());
        payload.put("grounded", result.grounded());
        payload.put("stats", result.stats());
        payload.put("metadata", metadata);
        payload.put("finishedAt", LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    private String buildFailurePayload(TaskRepository.TaskRow task, Exception e) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "qa.failed");
        payload.put("taskId", task.taskId());
        payload.put("status", "FAILED");
        payload.put("question", task.question());
        payload.put("error", abbreviate(e.getMessage()));
        payload.put("finishedAt", LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.length() <= 900 ? text : text.substring(0, 900);
    }
}
