package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PDF → Markdown（按页抽取 + 页码偏移记录 + 扫描页/图片页自动 OCR 兜底）。
 *
 * <p>升级说明（v2-Lite）：
 * <ul>
 *   <li><b>按页抽取</b>：精确记录每页在正文中的起始偏移 {@code pageOffsets}，让引用卡片能精确标注「第 N 页」；</li>
 *   <li><b>图片页/扫描页自动 OCR</b>：若某页抽取到的文本不足 30 字且 OCR 服务已开启，
 *       自动将该页渲染为 150 DPI PNG 交由本地 RapidOCR 识别（单文档最多自动 OCR 前 20 页以保护 3C4G 内存）。</li>
 * </ul>
 */
@Component
public class PdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);
    private static final Set<String> EXT = Set.of("pdf");
    /** 单页少于这么多字符，视为无文本层（扫描页/纯图片页）。 */
    private static final int MIN_CHARS_PER_PAGE = 30;
    /** 单份 PDF 最多自动 OCR 前 20 页，避免几百页扫描件把小内存服务器打满。 */
    private static final int MAX_OCR_PAGES = 20;
    private static final float OCR_RENDER_DPI = 150f;

    private final OcrClient ocrClient;

    public PdfParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = Math.max(1, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = ocrClient.isEnabled() ? new PDFRenderer(document) : null;

            StringBuilder full = new StringBuilder();
            List<Integer> pageOffsets = new ArrayList<>(pages);
            int ocrPages = 0;

            for (int p = 1; p <= pages; p++) {
                pageOffsets.add(full.length());
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = cleanPage(stripper.getText(document));

                if (pageText.length() < MIN_CHARS_PER_PAGE && renderer != null && ocrPages < MAX_OCR_PAGES) {
                    Optional<String> ocrText = ocrPage(renderer, p, fileName);
                    if (ocrText.isPresent()) {
                        pageText = pageText.isBlank() ? ocrText.get() : pageText + "\n\n" + ocrText.get();
                        ocrPages++;
                    }
                }

                if (!pageText.isBlank()) {
                    if (!full.isEmpty()) {
                        full.append("\n\n");
                    }
                    full.append(pageText);
                }
            }

            if (ocrPages > 0) {
                log.info("PDF 解析完成（含扫描/图片页自动 OCR）file={} pages={} ocrPages={}", fileName, pages, ocrPages);
            }

            String cleaned = full.toString().strip();
            if (cleaned.length() < Math.min(pages, 3) * MIN_CHARS_PER_PAGE && cleaned.length() < MIN_CHARS_PER_PAGE) {
                throw ApiException.badRequest("PDF_NO_TEXT_LAYER",
                        "该 PDF 未抽到有效文本（共 " + pages + " 页，仅抽到 " + cleaned.length() + " 字符）。"
                                + "若为扫描件，请确认 OCR 服务已开启，或使用 scripts/pdf2md.ps1 转成 Markdown 后再入库。");
            }

            return new Parsed(cleaned, "pdf", pageOffsets);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("PARSE_PDF_FAILED", "PDF 解析失败：" + e.getMessage());
        }
    }

    private Optional<String> ocrPage(PDFRenderer renderer, int pageNumber, String fileName) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, OCR_RENDER_DPI);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return ocrClient.recognizeQuietly(out.toByteArray(), fileName + "-p" + pageNumber + ".png");
        } catch (Exception e) {
            log.warn("PDF 第 {} 页渲染 OCR 跳过 file={} reason={}", pageNumber, fileName, e.getMessage());
            return Optional.empty();
        }
    }

    private static String cleanPage(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
