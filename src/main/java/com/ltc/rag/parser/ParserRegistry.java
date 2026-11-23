package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解析器路由：按扩展名选择实现。
 *
 * <p>新增格式只需实现 {@link DocumentParser} 并注册为 Bean，这里不用改。
 */
@Component
public class ParserRegistry {

    private final Map<String, DocumentParser> byExtension;

    public ParserRegistry(List<DocumentParser> parsers) {
        Map<String, DocumentParser> map = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String ext : parser.extensions()) {
                map.put(ext.toLowerCase(Locale.ROOT), parser);
            }
        }
        this.byExtension = Map.copyOf(map);
    }

    public Set<String> supportedExtensions() {
        return new TreeSet<>(byExtension.keySet());
    }

    public DocumentParser.Parsed parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "文件内容为空");
        }
        String ext = extensionOf(fileName);
        DocumentParser parser = byExtension.get(ext);
        if (parser == null) {
            throw ApiException.badRequest("UNSUPPORTED_FORMAT",
                    "不支持的格式 ." + ext + "，当前支持：" + String.join(", ", supportedExtensions()));
        }
        DocumentParser.Parsed parsed = parser.parse(bytes, fileName);
        if (parsed.markdown() == null || parsed.markdown().isBlank()) {
            throw ApiException.badRequest("EMPTY_DOCUMENT", "解析后没有可用文本，请检查文档是否为空或加密");
        }
        return parsed;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
