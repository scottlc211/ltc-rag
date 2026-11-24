package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word → Markdown（支持内嵌图片原位 OCR）。
 *
 * <p>关键点：
 * <ol>
 *   <li><b>按 body 顺序遍历</b>（getBodyElements），表格、段落与内嵌图片按原文相对位置还原；</li>
 *   <li><b>内嵌图片自动 OCR</b>：当开启 OCR 服务时，段落内嵌的流程图/数据库表截图会自动识别为 Markdown 插入原位，
 *       彻底解决"整篇 Word 贴截图或图文混排无法入库"的问题。</li>
 * </ol>
 */
@Component
public class DocxParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxParser.class);
    private static final Set<String> EXT = Set.of("docx");
    /** 中文 Word 的标题样式名为"标题 1"；英文是 "Heading 1"；也可能是内置 styleId "Heading1"。 */
    private static final Pattern HEADING_DIGIT = Pattern.compile("(?:heading|标题|titre)\\s*([1-6])");
    /** 小于 5KB 的图片通常是图标/装饰线条，跳过 OCR 以节省耗时。 */
    private static final int MIN_IMAGE_BYTES = 5 * 1024;
    /** 单份 Word 最多自动 OCR 前 20 张图片，防止超多图文档阻塞 3C4G CPU。 */
    private static final int MAX_OCR_IMAGES = 20;

    private final OcrClient ocrClient;

    public DocxParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        rejectNonDocx(bytes);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder md = new StringBuilder();
            int[] ocrCount = {0};
            Set<String> seenPics = new HashSet<>();

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(md, paragraph, ocrCount, seenPics);
                } else if (element instanceof XWPFTable table) {
                    appendTable(md, table, ocrCount, seenPics);
                }
            }

            // 兜底：若段落遍历未覆盖到全部内嵌图片（如浮动文本框/页眉外独立图）且正文仍为空，尝试扫描全文档图片
            if (md.toString().isBlank() && ocrClient.isEnabled()) {
                for (XWPFPictureData pic : doc.getAllPictures()) {
                    if (ocrCount[0] >= MAX_OCR_IMAGES) {
                        break;
                    }
                    appendPictureOcr(md, pic, ocrCount, seenPics);
                }
            }

            if (ocrCount[0] > 0) {
                log.info("Word 解析完成并自动 OCR 内嵌图片 file={} ocrImages={}", fileName, ocrCount[0]);
            }

            String text = md.toString().replaceAll("\n{3,}", "\n\n").strip();
            if (text.isBlank()) {
                throw ApiException.badRequest("DOCX_NO_TEXT",
                        "这个 docx 里没抽到任何文字（且内嵌图片未识别出文本）。"
                                + "请确认文档包含可读文本，或开启 OCR 服务后重试。");
            }
            return new Parsed(text, "docx");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("PARSE_DOCX_FAILED", "Word 解析失败：" + e.getMessage());
        }
    }

    /**
     * 提前按文件头判断格式。
     */
    private static void rejectNonDocx(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw ApiException.badRequest("DOCX_EMPTY", "文件是空的或者不完整");
        }
        boolean ole2 = (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
        if (ole2) {
            throw ApiException.badRequest("DOC_LEGACY_FORMAT",
                    "这个文件的真实格式是 Word 97-2003 的 .doc（扩展名被改成了 .docx）。"
                            + "请用 Word/WPS 打开后「另存为」.docx，或者先转成 Markdown 再上传。");
        }
        boolean zip = bytes[0] == 'P' && bytes[1] == 'K';
        if (!zip) {
            throw ApiException.badRequest("DOCX_NOT_ZIP",
                    "不是有效的 .docx（docx 本质是 zip 包）。可能文件损坏，或实际是别的格式改了扩展名。");
        }
    }

    private void appendParagraph(StringBuilder md, XWPFParagraph paragraph, int[] ocrCount, Set<String> seenPics) {
        String text = paragraph.getText();
        if (text != null && !text.isBlank()) {
            int level = headingLevel(paragraph);
            if (level > 0) {
                md.append("#".repeat(level)).append(' ').append(text.strip()).append("\n\n");
            } else if (paragraph.getNumID() != null) {
                md.append("- ").append(text.strip()).append('\n');
            } else {
                md.append(text.strip()).append("\n\n");
            }
        }
        if (ocrClient.isEnabled() && ocrCount[0] < MAX_OCR_IMAGES) {
            for (XWPFRun run : paragraph.getRuns()) {
                for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    if (ocrCount[0] >= MAX_OCR_IMAGES) {
                        break;
                    }
                    appendPictureOcr(md, picture.getPictureData(), ocrCount, seenPics);
                }
            }
        }
    }

    private void appendPictureOcr(StringBuilder md, XWPFPictureData picData, int[] ocrCount, Set<String> seenPics) {
        if (picData == null) {
            return;
        }
        String picKey = picData.getFileName() + ":" + picData.getChecksum();
        if (!seenPics.add(picKey)) {
            return;
        }
        byte[] data = picData.getData();
        if (data == null || data.length < MIN_IMAGE_BYTES) {
            return;
        }
        String ext = picData.suggestFileExtension();
        if (ext == null || ext.isBlank()) {
            ext = "png";
        }
        // WMF/EMF 矢量图无法被 PIL 直接解码，跳过避免无谓报错
        String lowerExt = ext.toLowerCase(Locale.ROOT);
        if ("wmf".equals(lowerExt) || "emf".equals(lowerExt) || "svg".equals(lowerExt)) {
            return;
        }
        ocrCount[0]++;
        Optional<String> recognized = ocrClient.recognizeQuietly(data, "embedded." + lowerExt);
        recognized.ifPresent(ocrMd -> md.append("\n").append(ocrMd).append("\n\n"));
    }

    private static int headingLevel(XWPFParagraph paragraph) {
        String styleId = paragraph.getStyle();
        String styleName = paragraph.getStyleID() == null ? "" : paragraph.getStyleID();
        for (String candidate : new String[]{styleId, styleName}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            Matcher matcher = HEADING_DIGIT.matcher(lower);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            if (lower.equals("title") || lower.equals("标题")) {
                return 1;
            }
        }
        return 0;
    }

    private void appendTable(StringBuilder md, XWPFTable table, int[] ocrCount, Set<String> seenPics) {
        boolean headerDone = false;
        StringBuilder tableEmbeddedOcr = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder("|");
            int cells = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText() == null ? "" : cell.getText().strip().replace("|", "\\|").replace("\n", " ");
                line.append(' ').append(cellText).append(" |");
                cells++;
                if (ocrClient.isEnabled() && ocrCount[0] < MAX_OCR_IMAGES) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        for (XWPFRun run : p.getRuns()) {
                            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                                if (ocrCount[0] >= MAX_OCR_IMAGES) {
                                    break;
                                }
                                appendPictureOcr(tableEmbeddedOcr, picture.getPictureData(), ocrCount, seenPics);
                            }
                        }
                    }
                }
            }
            if (cells == 0) {
                continue;
            }
            md.append(line).append('\n');
            if (!headerDone) {
                md.append('|').append(" --- |".repeat(cells)).append('\n');
                headerDone = true;
            }
        }
        md.append('\n');
        if (!tableEmbeddedOcr.isEmpty()) {
            md.append(tableEmbeddedOcr);
        }
    }
}
