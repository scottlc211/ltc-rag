package com.ltc.rag.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * HTML → Markdown。
 *
 * <p>只保留对检索有价值的结构：标题层级、段落、列表、代码块、表格。
 * 导航/脚本/页脚这类噪声直接摘除——它们进向量库只会稀释召回。
 */
@Component
public class HtmlParser implements DocumentParser {

    private static final Set<String> EXT = Set.of("html", "htm");

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        org.jsoup.nodes.Document doc = Jsoup.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        doc.select("script, style, nav, footer, header, aside, noscript, iframe").remove();
        Element body = doc.body();

        StringBuilder md = new StringBuilder();
        for (Node node : body.childNodes()) {
            appendNode(md, node);
        }

        String markdown = md.toString().replaceAll("\n{3,}", "\n\n").strip();
        return new Parsed(markdown, "html");
    }

    private void appendNode(StringBuilder md, Node node) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text().strip();
            if (!text.isEmpty()) {
                md.append(text).append("\n\n");
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                md.append("#".repeat(level)).append(' ').append(element.text().strip()).append("\n\n");
            }
            case "p" -> md.append(element.text().strip()).append("\n\n");
            case "li" -> md.append("- ").append(element.text().strip()).append('\n');
            case "pre" -> md.append("```\n").append(element.wholeText().strip()).append("\n```\n\n");
            case "table" -> md.append(toMarkdownTable(element)).append("\n\n");
            case "ul", "ol", "div", "section", "article", "main" -> {
                for (Node child : element.childNodes()) {
                    appendNode(md, child);
                }
            }
            default -> {
                String text = element.text().strip();
                if (!text.isEmpty()) {
                    md.append(text).append("\n\n");
                }
            }
        }
    }

    private static String toMarkdownTable(Element table) {
        StringBuilder sb = new StringBuilder();
        boolean headerDone = false;
        for (Element tr : table.select("tr")) {
            StringBuilder row = new StringBuilder("|");
            for (Element cell : tr.select("th, td")) {
                row.append(' ').append(cell.text().strip().replace("|", "\\|")).append(" |");
            }
            row.append('\n');
            sb.append(row);
            if (!headerDone) {
                sb.append('|').append(" --- |".repeat(Math.max(1, tr.select("th, td").size()))).append('\n');
                headerDone = true;
            }
        }
        return sb.toString().stripTrailing();
    }
}
