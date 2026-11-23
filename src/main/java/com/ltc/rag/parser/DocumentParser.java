package com.ltc.rag.parser;

import java.util.Set;

/**
 * 文档解析器：把任意格式统一成 Markdown。
 *
 * <p>统一成 Markdown 的原因：标题层级是切分的基础，表格/代码在 Markdown 里有明确边界，
 * 比"一把纯文本"能保住更多结构信息。
 */
public interface DocumentParser {

    /**
     * @param markdown 解析后的 Markdown（标题用 #，表用 |，代码用 ```）
     * @param format   归一化后的格式标识，如 docx/pdf/md
     */
    record Parsed(String markdown, String format) {
    }

    /** 支持的扩展名（小写、不含点）。 */
    Set<String> extensions();

    Parsed parse(byte[] bytes, String fileName);
}
