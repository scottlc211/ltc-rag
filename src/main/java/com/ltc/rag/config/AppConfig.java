package com.ltc.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基础设施 Bean：HTTP 客户端与工作线程池。
 *
 * <p>不用 WebClient/Reactor：本项目只有"发请求 + 逐行读 SSE"，JDK HttpClient 足够，
 * 少一层响应式栈，3C4G 上少一份内存和心智负担。
 */
@Configuration
public class AppConfig {

    /**
     * 构造带指定读超时的 RestClient，避免 Rerank（15s）或 Embedding（60s）被 Chat 的 180s 超时拖死。
     */
    public static RestClient buildRestClient(int readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(5, readTimeoutSeconds)));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 默认 RestClient（用于 Chat 同步请求、Qdrant 与本地 OCR）。
     */
    @Bean
    public RestClient ragRestClient(RagProperties props) {
        return buildRestClient(props.chat().timeoutSeconds());
    }

    /**
     * 问答/回调的并行执行器。3C4G 只有 3 核，8 个线程够用：
     * 瓶颈在远程 LLM，线程基本都在等 IO。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "rag-worker-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(8, factory);
    }
}
