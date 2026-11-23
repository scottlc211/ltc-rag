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
import java.util.Set;

/**
 * 图片 → Markdown（靠本地 OCR 服务，见 scripts/embed_server.py 的 /v1/ocr）。
 *
 * <p>为什么不自己用 Java 做 OCR：Tesseract 的中文识别质量明显不如 PP-OCR，
 * 而项目里已经跑了 embedding 服务（同一个 venv 里有 RapidOCR），复用它不增加任何进程。
 *
 * <p>识别结果会带表格结构：截图里的表会被还原成 Markdown 表格，
 * 这样切块后每行仍带着表头和上下文，而不是一堆散落的词。
 */
@Component
public class ImageParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageParser.class);
    private static final Set<String> EXT = Set.of("png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff");

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final RagProperties.Ocr cfg;
    private final String endpoint;

    public ImageParser(RestClient ragRestClient, ObjectMapper objectMapper, RagProperties props) {
        this.http = ragRestClient;
        this.objectMapper = objectMapper;
        this.cfg = props.ocr();
        this.endpoint = stripTrailingSlash(cfg.baseUrl()) + "/v1/ocr";
        if (!cfg.enabled()) {
            log.warn("OCR 已关闭：直接上传图片会被拒绝，需要先用 scripts\\to-md.ps1 转换");
        }
    }

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        if (!cfg.enabled()) {
            throw ApiException.badRequest("OCR_DISABLED",
                    "图片入库需要 OCR，但 rag.ocr.enabled=false。"
                            + "请设置 OCR_ENABLED=true 并用 scripts\\embed-up.ps1 启动识别服务，"
                            + "或者先在本地用 scripts\\to-md.ps1 转成 Markdown 再上传。");
        }

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
                    "图片识别服务不可用（" + endpoint + "）：" + e.getMessage()
                            + "。启动方式：scripts\\embed-up.ps1", e);
        }

        String markdown;
        try {
            JsonNode root = objectMapper.readTree(json);
            markdown = root.path("markdown").asText("");
        } catch (Exception e) {
            throw ApiException.badGateway("OCR_BAD_RESPONSE", "OCR 服务返回的不是预期格式：" + json, e);
        }

        if (markdown.isBlank()) {
            throw ApiException.badRequest("OCR_NO_TEXT",
                    "这张图里没识别出文字。可能是纯图片/照片/图表，或者分辨率太低。");
        }
        log.info("图片 OCR 完成 file={} chars={}", fileName, markdown.length());
        return new Parsed("# " + stripExtension(fileName) + "\n\n" + markdown, "image");
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

    /** 提取文件扩展名（不含点号，全小写），用于准确设置 OCR 请求的 Content-Type。 */
    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    /** 文件名去掉扩展名，用作标题（避免整份文档只有表格没有标题）。 */
    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "图片";
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "图片" : base;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8091";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
