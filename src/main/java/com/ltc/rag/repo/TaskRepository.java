package com.ltc.rag.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 异步任务与回调发件箱。
 *
 * <p>两者都用"数据库队列"而不是 MQ：3C4G 上少一个中间件，
 * 且 FOR UPDATE SKIP LOCKED 天然支持多实例抢任务，重启不丢。
 */
@Repository
public class TaskRepository {

    public record TaskRow(long id, String taskId, Long apiKeyId, String namespace, String question,
                          String options, String callbackUrl, String callbackSecret, String status,
                          String answer, String citations, String error, int attempts,
                          LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record OutboxRow(long id, String taskId, String url, String payload, int attempts, String secret) {
    }

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, i) -> new TaskRow(
            rs.getLong("id"),
            rs.getString("task_id"),
            (Long) rs.getObject("api_key_id"),
            rs.getString("namespace"),
            rs.getString("question"),
            rs.getString("options"),
            rs.getString("callback_url"),
            rs.getString("callback_secret"),
            rs.getString("status"),
            rs.getString("answer"),
            rs.getString("citations"),
            rs.getString("error"),
            rs.getInt("attempts"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String taskId, Long apiKeyId, String namespace, String question, String options,
                       String callbackUrl, String callbackSecret) {
        jdbc.update("""
                INSERT INTO kb_task (task_id, api_key_id, namespace, question, options, callback_url,
                                     callback_secret, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, taskId, apiKeyId, namespace, question, options, callbackUrl, callbackSecret);
    }

    public Optional<TaskRow> findByTaskId(String taskId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT id, task_id, api_key_id, namespace, question, options, callback_url, callback_secret,
                       status, answer, citations, error, attempts, created_at, started_at, finished_at
                FROM kb_task WHERE task_id = ?
                """, TASK_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 抢占一个待执行任务（必须在事务内调用）。
     * SKIP LOCKED 让多实例并行消费而不会重复执行同一条。
     */
    public Optional<Long> claimNext() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM kb_task WHERE status = 'PENDING' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                Long.class);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        Long id = ids.get(0);
        jdbc.update("UPDATE kb_task SET status = 'RUNNING', started_at = ?, attempts = attempts + 1 WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), id);
        return Optional.of(id);
    }

    /**
     * 回收僵尸 RUNNING 任务：容器在执行途中被重启或 OOM 时，任务会卡在 RUNNING。
     *
     * <ul>
     *   <li>若 attempts &lt; maxAttempts：退回 PENDING 重新执行；</li>
     *   <li>若已达最大尝试次数：直接标记为 FAILED，防止无限循环。</li>
     * </ul>
     */
    public int recoverStaleRunning(LocalDateTime staleBefore, int maxAttempts) {
        Timestamp threshold = Timestamp.valueOf(staleBefore);
        int retried = jdbc.update("""
                UPDATE kb_task
                   SET status = 'PENDING'
                 WHERE status = 'RUNNING'
                   AND started_at <= ?
                   AND attempts < ?
                """, threshold, maxAttempts);
        int failed = jdbc.update("""
                UPDATE kb_task
                   SET status = 'FAILED',
                       error = '任务执行超时或进程异常中断',
                       finished_at = ?
                 WHERE status = 'RUNNING'
                   AND started_at <= ?
                   AND attempts >= ?
                """, Timestamp.valueOf(LocalDateTime.now()), threshold, maxAttempts);
        return retried + failed;
    }

    public Optional<TaskRow> findById(long id) {
        List<TaskRow> rows = jdbc.query("""
                SELECT id, task_id, api_key_id, namespace, question, options, callback_url, callback_secret,
                       status, answer, citations, error, attempts, created_at, started_at, finished_at
                FROM kb_task WHERE id = ?
                """, TASK_MAPPER, id);
        return rows.stream().findFirst();
    }

    public void markSucceeded(long id, String answer, String citationsJson) {
        jdbc.update("""
                UPDATE kb_task SET status = 'SUCCEEDED', answer = ?, citations = ?, error = NULL, finished_at = ?
                WHERE id = ?
                """, answer, citationsJson, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("""
                UPDATE kb_task SET status = 'FAILED', error = ?, finished_at = ? WHERE id = ?
                """, error, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void enqueueCallback(String taskId, String url, String payload) {
        jdbc.update("""
                INSERT INTO kb_callback_outbox (task_id, url, payload, status, next_retry_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """, taskId, url, payload, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 取出到期的回调（连表拿签名密钥，避免密钥散落在多个地方）。 */
    public List<OutboxRow> findDueCallbacks(int limit) {
        return jdbc.query("""
                SELECT o.id, o.task_id, o.url, o.payload, o.attempts, t.callback_secret
                FROM kb_callback_outbox o
                JOIN kb_task t ON t.task_id = o.task_id
                WHERE o.status = 'PENDING' AND o.next_retry_at <= ?
                ORDER BY o.id
                LIMIT ?
                """,
                (rs, i) -> new OutboxRow(
                        rs.getLong("id"), rs.getString("task_id"), rs.getString("url"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getString("callback_secret")),
                Timestamp.valueOf(LocalDateTime.now()), limit);
    }

    public void markCallbackSent(long id, int code) {
        jdbc.update("""
                UPDATE kb_callback_outbox SET status = 'SENT', attempts = attempts + 1,
                       last_code = ?, last_error = NULL WHERE id = ?
                """, code, id);
    }

    public void markCallbackRetry(long id, int attempts, LocalDateTime nextRetryAt, Integer code, String error) {
        jdbc.update("""
                UPDATE kb_callback_outbox SET attempts = ?, next_retry_at = ?, last_code = ?, last_error = ?
                WHERE id = ?
                """, attempts, Timestamp.valueOf(nextRetryAt), code, truncate(error, 1000), id);
    }

    public void markCallbackDead(long id, int attempts, Integer code, String error) {
        jdbc.update("""
                UPDATE kb_callback_outbox SET status = 'FAILED', attempts = ?, last_code = ?, last_error = ?
                WHERE id = ?
                """, attempts, code, truncate(error, 1000), id);
    }

    /** 手动重放：把最近一条发件箱记录打回待发送，用于死信补偿。 */
    public boolean resetCallback(String taskId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM kb_callback_outbox WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, taskId);
        if (ids.isEmpty()) {
            return false;
        }
        jdbc.update("""
                UPDATE kb_callback_outbox SET status = 'PENDING', attempts = 0, next_retry_at = ?
                WHERE id = ?
                """, Timestamp.valueOf(LocalDateTime.now()), ids.get(0));
        return true;
    }

    public int countOutboxByStatus(String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_callback_outbox WHERE status = ?", Integer.class, status);
        return n == null ? 0 : n;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 保留给运维排查：最近任务列表。 */
    public List<TaskRow> recent(int limit) {
        return jdbc.query("""
                SELECT id, task_id, api_key_id, namespace, question, options, callback_url, callback_secret,
                       status, answer, citations, error, attempts, created_at, started_at, finished_at
                FROM kb_task ORDER BY id DESC LIMIT ?
                """, TASK_MAPPER, limit);
    }
}
