package com.ltc.rag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;
import java.util.Optional;

/**
 * 统一本地 OCR 客户端（调用 scripts/embed_server.py 的 POST /v1/ocr）。
 *
 * <p>同时被三处复用：
 * <ol>
 *   <li>{@link ImageParser}：直接上传 png/jpg 截图；</li>
 *   <li>{@link DocxParser}：Word 文档中嵌入的流程图/表结构截图自动识别；</li>
 *   <li>{@link PdfParser}：PDF 扫描页或纯图片页自动渲染为 PNG 走 OCR 识别。</li>
 * </ol>
 */
@Component
public class OcrClient {

    private static final Logger log = LoggerFactory.getLogger(OcrClient.class);

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final RagProperties.Ocr cfg;
    private final String endpoint;

    public OcrClient(RestClient ragRestClient, ObjectMapper objectMapper, RagProperties props) {
        this.http = ragRestClient;
        this.objectMapper = objectMapper;
        this.cfg = props.ocr();
        this.endpoint = stripTrailingSlash(cfg.baseUrl()) + "/v1/ocr";
        if (!cfg.enabled()) {
            log.warn("OCR 已关闭：直接上传图片或文档内嵌图片将跳过识别（rag.ocr.enabled=false）");
        }
    }

    public boolean isEnabled() {
        return cfg.enabled();
    }

    /**
     * 严格模式 OCR（供直接上传图片使用）：OCR 关闭、服务不可用或无文字时显式抛出 ApiException。
     */
    public String recognizeStrict(byte[] bytes, String fileName) {
        if (!cfg.enabled()) {
            throw ApiException.badRequest("OCR_DISABLED",
                    "图片入库需要 OCR，但 rag.ocr.enabled=false。"
                            + "请开启 OCR_ENABLED=true 并启动识别服务，或先本地转成 Markdown 再上传。");
        }
        String markdown = callOcr(bytes, fileName);
        if (markdown.isBlank()) {
            throw ApiException.badRequest("OCR_NO_TEXT",
                    "这张图里没识别出文字。可能是纯图片/照片/图表，或者分辨率太低。");
        }
        return markdown;
    }

    /**
     * 容错模式 OCR（供 Word/PDF 内嵌图片或扫描页使用）：
     * 装饰图标/纯背景图识别不出文字或单张失败时静默跳过，绝不中断整篇文档的解析。
     */
    public Optional<String> recognizeQuietly(byte[] bytes, String fileName) {
        if (!cfg.enabled() || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            String markdown = callOcr(bytes, fileName);
            return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
        } catch (Exception e) {
            log.warn("内嵌图片 OCR 跳过 file={} reason={}", fileName, e.getMessage());
            return Optional.empty();
        }
    }

    private String callOcr(byte[] bytes, String fileName) {
        String json;
        try {
            json = http.post()
                    .uri(endpoint)
                    .contentType(mediaTypeOf(fileName))
                    .body(bytes)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw ApiException.badGateway("OCR_UNAVAILABLE",
                    "图片识别服务不可用（" + endpoint + "）：" + e.getMessage(), e);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("markdown").asText("").strip();
        } catch (Exception e) {
            throw ApiException.badGateway("OCR_BAD_RESPONSE", "OCR 服务返回的不是预期格式：" + json, e);
        }
    }

    private static MediaType mediaTypeOf(String fileName) {
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "bmp", "webp", "tif", "tiff" -> MediaType.parseMediaType("image/" + ext);
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8091";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
