package com.ltc.rag.repo;

import com.ltc.rag.domain.AnswerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 问答审计落库。写失败只记日志，绝不影响用户拿到答案。
 */
@Repository
public class QaLogRepository {

    private static final Logger log = LoggerFactory.getLogger(QaLogRepository.class);

    private final JdbcTemplate jdbc;

    public QaLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String mode, String taskId, Long apiKeyId, String namespace,
                       String question, AnswerResult result, long totalMs) {
        try {
            String answer = result.answer() == null ? "" : result.answer();
            jdbc.update("""
                    INSERT INTO kb_qa_log
                        (task_id, api_key_id, mode, namespace, question, answer_chars, hit_count,
                         retrieval_ms, llm_ms, total_ms, prompt_tokens, completion_tokens)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    taskId, apiKeyId, mode, namespace,
                    truncate(question, 2000), answer.length(), result.citations().size(),
                    result.stats().retrievalMs(), result.stats().llmMs(), totalMs,
                    toInt(result.stats().promptTokens()), toInt(result.stats().completionTokens()));
        } catch (RuntimeException e) {
            log.warn("写问答审计失败（不影响响应）", e);
        }
    }

    private static Integer toInt(Integer value) {
        return value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 便于运维按天看调用量（可选接口用）。 */
    public List<java.util.Map<String, Object>> dailyStats() {
        return jdbc.queryForList("""
                SELECT DATE(created_at) AS day, COUNT(*) AS calls, SUM(hit_count) AS hits
                FROM kb_qa_log GROUP BY DATE(created_at) ORDER BY day DESC LIMIT 30
                """);
    }

    /** 统计某个 API Key 当天的问答调用次数，用于每日配额（quota_per_day）限流。 */
    public int countTodayCalls(long apiKeyId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM kb_qa_log
                 WHERE api_key_id = ?
                   AND created_at >= CURDATE()
                """, Integer.class, apiKeyId);
        return n == null ? 0 : n;
    }
}
