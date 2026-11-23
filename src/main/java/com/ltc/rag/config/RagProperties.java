package com.ltc.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调参数集中在这里，配置项与 application.yml 一一对应（宽松绑定）。
 *
 * <p>用 record 而不是 @Data 类：配置对象本来就不该被修改。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String defaultNamespace,
        Auth auth,
        Chunking chunking,
        Retrieval retrieval,
        Embedding embedding,
        Chat chat,
        Rerank rerank,
        Ocr ocr,
        Storage storage,
        Callback callback
) {

    public record Auth(boolean enabled) {
    }

    /**
     * @param maxChildChars  子块最大字数（中文 1 字 ≈ 1 token，bge-large-zh 上限 512）
     * @param overlapChars   相邻子块重叠字数，避免答案被切口截断
     * @param maxParentChars 父块最大字数，命中子块后回填它作为上下文
     */
    public record Chunking(int maxChildChars, int overlapChars, int maxParentChars) {
    }

    /**
     * @param vectorTopK    向量召回条数
     * @param fulltextTopK  BM25 召回条数
     * @param contextTopN   最终喂给模型的条数
     * @param rrfK          RRF 平滑常数，业界常用 60
     * @param minScore      向量余弦相似度下限，低于它的直接丢（避免噪声进 rerank）
     */
    public record Retrieval(int vectorTopK, int fulltextTopK, int contextTopN, int rrfK, double minScore) {
    }

    /**
     * @param enabled 关掉后完全不调用 embedding 服务（轻量模式：只走 BM25，无需外部 Key）
     */
    public record Embedding(boolean enabled, String baseUrl, String apiKey, String model,
                            int dimension, int batchSize, int timeoutSeconds) {
    }

    public record Chat(String baseUrl, String apiKey, String model, double temperature, int maxTokens, int timeoutSeconds) {
    }

    public record Rerank(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    /**
     * 图片 OCR（直接上传 png/jpg 时用）。
     *
     * @param baseUrl OCR 服务地址，默认指向和 embedding 同一个本地服务
     */
    public record Ocr(boolean enabled, String baseUrl) {
    }

    /**
     * @param enabled 关掉后不连 Qdrant（轻量模式：向量检索整体跳过，MySQL 单机可跑）
     */
    public record Storage(boolean enabled, String qdrantUrl, String collection) {
    }

    /**
     * @param maxAttempts        最大投递次数（含首次）
     * @param baseBackoffSeconds 退避基数：10s → 30s → 90s → 270s（指数 ×3）
     * @param allowPrivateIp     是否允许回调内网/环回地址（默认 false 防 SSRF，仅本地调试时可设为 true）
     */
    public record Callback(int maxAttempts, int timeoutSeconds, int baseBackoffSeconds, boolean allowPrivateIp) {
    }
}
