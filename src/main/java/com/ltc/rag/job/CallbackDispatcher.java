package com.ltc.rag.job;

import com.ltc.rag.common.ApiException;
import com.ltc.rag.common.Hmac;
import com.ltc.rag.common.SsrfGuard;
import com.ltc.rag.config.RagProperties;
import com.ltc.rag.repo.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回调投递器。
 *
 * <p>四条铁律（很多"加了回调"的项目一条都不满足）：
 * <ol>
 *   <li>先落库再投递：任务完成与回调送达解耦，进程挂了回调还在；</li>
 *   <li>HMAC 签名 + 时间戳：调用方可验签、可防重放；</li>
 *   <li>指数退避 + 最大次数：10s → 30s → 90s → 270s；</li>
 *   <li>死信可查可重放，而不是"失败就没了"。</li>
 * </ol>
 *
 * <p>语义是<b>至少一次</b>：调用方请用 taskId 做幂等去重（分布式下"恰好一次"无法保证）。
 */
@Component
public class CallbackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);

    private final TaskRepository repository;
    private final RagProperties props;
    private final HttpClient http;

    public CallbackDispatcher(TaskRepository repository, RagProperties props) {
        this.repository = repository;
        this.props = props;
        // 显式关闭自动重定向（Redirect.NEVER），防止公网回调 URL 通过 302 跳转到 127.0.0.1 绕过 SSRF 防护
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "3000")
    public void tick() {
        List<TaskRepository.OutboxRow> due;
        try {
            due = repository.findDueCallbacks(10);
        } catch (RuntimeException e) {
            log.error("读取回调发件箱失败", e);
            return;
        }
        for (TaskRepository.OutboxRow row : due) {
            deliver(row);
        }
    }

    private void deliver(TaskRepository.OutboxRow row) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = Hmac.hex(row.secret() == null ? "" : row.secret(), timestamp + "." + row.payload());

        HttpRequest request;
        try {
            // 投递前二次校验域名解析结果，防御 DNS Rebinding 攻击
            String safeUrl = SsrfGuard.validateCallbackUrl(row.url(), props.callback().allowPrivateIp());
            request = HttpRequest.newBuilder(URI.create(safeUrl))
                    .timeout(Duration.ofSeconds(props.callback().timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-LTC-Signature", "t=" + timestamp + ",v1=" + signature)
                    .header("X-LTC-Task-Id", row.taskId())
                    .POST(HttpRequest.BodyPublishers.ofString(row.payload(), StandardCharsets.UTF_8))
                    .build();
        } catch (ApiException | IllegalArgumentException e) {
            log.error("回调地址未通过安全校验，直接置死信 taskId={} url={} reason={}",
                    row.taskId(), row.url(), e.getMessage());
            repository.markCallbackDead(row.id(), row.attempts() + 1, null, "回调地址非法或触发 SSRF 拦截: " + e.getMessage());
            return;
        }

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                repository.markCallbackSent(row.id(), code);
                log.info("回调成功 taskId={} code={} 耗时={}ms", row.taskId(), code, response.uri().getPath());
                return;
            }
            retryOrDead(row, code, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryOrDead(row, null, "投递被中断");
        } catch (Exception e) {
            retryOrDead(row, null, e.getMessage());
        }
    }

    private void retryOrDead(TaskRepository.OutboxRow row, Integer code, String error) {
        int attempts = row.attempts() + 1;
        int maxAttempts = props.callback().maxAttempts();
        if (attempts >= maxAttempts) {
            repository.markCallbackDead(row.id(), attempts, code, error);
            log.error("回调进入死信 taskId={} attempts={} code={} error={}",
                    row.taskId(), attempts, code, abbreviate(error));
            return;
        }
        long delaySeconds = props.callback().baseBackoffSeconds() * (long) Math.pow(3, attempts - 1);
        repository.markCallbackRetry(row.id(), attempts, LocalDateTime.now().plusSeconds(delaySeconds), code, error);
        log.warn("回调失败，{}s 后重试（{}/{}）taskId={} code={} error={}",
                delaySeconds, attempts, maxAttempts, row.taskId(), code, abbreviate(error));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}
