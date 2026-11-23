package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PDF → 纯文本。
 *
 * <p><b>能力边界（务必知悉）</b>：PDFBox 只抽文本层，不做版面分析。
 * 双栏排版靠 setSortByPosition 能缓解，但复杂表格会被拉平，扫描件直接没有文本。
 * 所以：
 * <ul>
 *   <li>有文本层 → 可用；</li>
 *   <li>扫描件 → 明确报 PDF_NO_TEXT_LAYER，提示先用 OCR（ocrmypdf / MinerU）转换；</li>
 *   <li>表格密集型 PDF → 建议先在本地用 Docling 转 Markdown 再上传。</li>
 * </ul>
 * 这是 3C4G 无 GPU 场景下的现实取舍：解析重活放开发机，服务器只做检索。
 */
@Component
public class PdfParser implements DocumentParser {

    private static final Set<String> EXT = Set.of("pdf");
    /** 平均每页少于这么多字符，基本可以判定没有文本层（扫描件/纯图层）。 */
    private static final int MIN_CHARS_PER_PAGE = 30;

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
            String text = stripper.getText(document);
            int chars = text == null ? 0 : text.strip().length();

            if (chars < pages * MIN_CHARS_PER_PAGE) {
                throw ApiException.badRequest("PDF_NO_TEXT_LAYER",
                        "怀疑该 PDF 为扫描件（" + pages + " 页仅抽到 " + chars + " 字符）。"
                                + "请先用 OCR（如 ocrmypdf）或 Docling 转成 Markdown 后再入库。");
            }

            String cleaned = text
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("[ \\t]{2,}", " ")
                    .replaceAll("\n{3,}", "\n\n")
                    .strip();

            return new Parsed(cleaned, "pdf");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("PARSE_PDF_FAILED", "PDF 解析失败：" + e.getMessage());
        }
    }
}
