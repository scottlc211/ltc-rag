package com.ltc.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 图片 → Markdown（委托 {@link OcrClient} 调用本地 RapidOCR 服务）。
 *
 * <p>识别结果会带表格结构：截图里的表会被还原成 Markdown 表格，
 * 这样切块后每行仍带着表头和上下文，而不是一堆散落的词。
 */
@Component
public class ImageParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageParser.class);
    private static final Set<String> EXT = Set.of("png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff");

    private final OcrClient ocrClient;

    public ImageParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        String markdown = ocrClient.recognizeStrict(bytes, fileName);
        log.info("图片 OCR 完成 file={} chars={}", fileName, markdown.length());
        return new Parsed("# " + stripExtension(fileName) + "\n\n" + markdown, "image");
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
}
