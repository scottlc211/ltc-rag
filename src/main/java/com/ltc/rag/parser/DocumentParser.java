package com.ltc.rag.parser;

import java.util.List;
import java.util.Set;

/**
 * 文档解析器：把任意格式统一成 Markdown。
 *
 * <p>统一成 Markdown 的原因：标题层级是切分的基础，表格/代码在 Markdown 里有明确边界，
 * 比"一把纯文本"能保住更多结构信息。
 */
public interface DocumentParser {

    /**
     * @param markdown    解析后的 Markdown（标题用 #，表用 |，代码用 ```）
     * @param format      归一化后的格式标识，如 docx/pdf/md
     * @param pageOffsets PDF 每页在 markdown 中的起始字符偏移（下标 0 表示第 1 页），非 PDF 为空列表
     */
    record Parsed(String markdown, String format, List<Integer> pageOffsets) {

        public Parsed(String markdown, String format) {
            this(markdown, format, List.of());
        }
    }

    /** 支持的扩展名（小写、不含点）。 */
    Set<String> extensions();

    Parsed parse(byte[] bytes, String fileName);
}
