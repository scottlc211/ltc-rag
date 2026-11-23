package com.ltc.rag.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Markdown / 纯文本：原样返回，只做 BOM 与换行归一化。
 *
 * <p>LTC 这类技术文档如果直接维护 .md，这是质量最高的一条路径——
 * 不需要任何"猜测版面"的解析，标题就是标题。
 */
@Component
public class MarkdownParser implements DocumentParser {

    private static final Set<String> EXT = Set.of("md", "markdown", "txt", "text");

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // Windows 记事本保存的 UTF-8 会带 BOM，不清掉会让第一个标题识别失败
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String format = fileName.toLowerCase().endsWith(".txt") ? "txt" : "md";
        return new Parsed(text.stripTrailing(), format);
    }

    /**
     * 供 HTML/PDF 解析器复用的公共工具：把一行行文本收敛成段落，避免碎行影响切分。
     */
    static String joinParagraphs(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                    sb.append('\n');
                }
            } else {
                sb.append(line.strip()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
