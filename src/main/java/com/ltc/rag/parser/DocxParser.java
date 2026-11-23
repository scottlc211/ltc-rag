package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word → Markdown。
 *
 * <p>关键点：<b>按 body 顺序遍历</b>（getBodyElements），表格出现在哪个段落之后，
 * 结果里就还在那个位置。很多实现把表格统一追加到文末，术语表/参数表就彻底失序了。
 */
@Component
public class DocxParser implements DocumentParser {

    private static final Set<String> EXT = Set.of("docx");
    /** 中文 Word 的标题样式名为"标题 1"；英文是 "Heading 1"；也可能是内置 styleId "Heading1"。 */
    private static final Pattern HEADING_DIGIT = Pattern.compile("(?:heading|标题|titre)\\s*([1-6])");

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        rejectNonDocx(bytes);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder md = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(md, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendTable(md, table);
                }
            }
            String text = md.toString().replaceAll("\n{3,}", "\n\n").strip();
            if (text.isBlank()) {
                throw ApiException.badRequest("DOCX_NO_TEXT",
                        "这个 docx 里没抽到任何文字，通常是整篇只贴了一张图片/截图。"
                                + "请先用 scripts\\img2md.py（图片→Markdown）或 scripts\\pdf2md.ps1（PDF→Markdown）转换，"
                                + "再把生成的 Markdown 上传。");
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
     *
     * <p>现实里最常见的两种情况：把老式 .doc 直接改扩展名成 .docx，
     * 或者文件根本没下载完。POI 抛出来的异常是 "End of Central Directory record could not be found"，
     * 用户完全看不懂，所以这里换成能照做的提示。
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

    private static void appendParagraph(StringBuilder md, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        int level = headingLevel(paragraph);
        if (level > 0) {
            md.append("#".repeat(level)).append(' ').append(text.strip()).append("\n\n");
        } else if (paragraph.getNumID() != null) {
            // 列表项：Word 里编号是渲染期生成的，getText() 拿不到序号，用 - 兜底
            md.append("- ").append(text.strip()).append('\n');
        } else {
            md.append(text.strip()).append("\n\n");
        }
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

    private static void appendTable(StringBuilder md, XWPFTable table) {
        boolean headerDone = false;
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder("|");
            int cells = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                line.append(' ').append(cell.getText().strip().replace("|", "\\|")).append(" |");
                cells++;
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
    }
}
