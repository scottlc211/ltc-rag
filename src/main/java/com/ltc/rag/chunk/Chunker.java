package com.ltc.rag.chunk;

import com.ltc.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题感知切分 + 父子块。
 *
 * <p>为什么不用等长切分：技术文档的语义单元是"章节"，不是"第 480 个字"。
 * 按标题切，检索命中后天然知道自己在哪个模块；章节过长时再拆子块，
 * 并保留父块用于回填上下文。
 *
 * <p>两个容易踩的坑这里都处理了：
 * <ol>
 *   <li>代码块里的 {@code #} 不是标题（fenced code 内跳过标题识别）；</li>
 *   <li>子块之间保留 overlap，避免一句话被切口截断导致检索不到。</li>
 * </ol>
 */
@Component
public class Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;\\n]?");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");

    private final int maxChildChars;
    private final int overlapChars;
    private final int maxParentChars;

    public Chunker(RagProperties props) {
        this.maxChildChars = Math.max(120, props.chunking().maxChildChars());
        this.overlapChars = Math.max(0, Math.min(props.chunking().overlapChars(), maxChildChars / 2));
        this.maxParentChars = Math.max(maxChildChars, props.chunking().maxParentChars());
    }

    public List<ChunkPiece> split(String markdown) {
        List<ChunkPiece> pieces = new ArrayList<>();
        int index = 0;

        for (Section section : readSections(markdown)) {
            String body = section.body().strip();
            if (body.isEmpty()) {
                continue;
            }
            String heading = section.headingPath();
            // 标题路径会拼进正文，预算要把它先扣掉，保证"块总长 ≤ maxChildChars"
            int budget = budgetFor(heading);
            String block = compose(heading, body);

            if (body.length() <= budget) {
                pieces.add(new ChunkPiece(ChunkPiece.CHILD, index++, null, heading, block, block.length()));
                continue;
            }

            // 章节过长：先落一个父块承载整节语义，再拆子块承载细节
            int parentIndex = index;
            String parentText = compose(heading, truncate(body, maxParentChars));
            pieces.add(new ChunkPiece(ChunkPiece.PARENT, index++, null, heading, parentText, parentText.length()));

            for (String part : splitLong(body, budget)) {
                String child = compose(heading, part);
                pieces.add(new ChunkPiece(ChunkPiece.CHILD, index++, parentIndex, heading, child, child.length()));
            }
        }
        return pieces;
    }

    private record Section(String headingPath, String body) {
    }

    /**
     * 按标题把 Markdown 切成小节，同时维护标题栈生成路径。
     */
    private static List<Section> readSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();
        String currentPath = "";
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            Matcher matcher = inFence ? null : HEADING.matcher(line);
            if (matcher != null && matcher.matches()) {
                flush(sections, currentPath, body);
                int level = matcher.group(1).length();
                String title = matcher.group(2).strip();
                while (stack.size() >= level) {
                    stack.removeLast();
                }
                stack.addLast(title);
                currentPath = String.join(" > ", stack);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(sections, currentPath, body);
        return sections;
    }

    private static void flush(List<Section> sections, String headingPath, StringBuilder body) {
        String text = body.toString();
        if (!text.isBlank()) {
            sections.add(new Section(headingPath, text));
        }
        body.setLength(0);
    }

    private int budgetFor(String heading) {
        int headingCost = heading.isEmpty() ? 0 : heading.length() + 1;
        return Math.max(120, maxChildChars - headingCost);
    }

    /**
     * 长文本拆分：段落优先 → 句子 → 硬切，最后补 overlap。
     */
    private List<String> splitLong(String text, int budget) {
        List<String> units = new ArrayList<>();
        for (String paragraph : text.split("\n{2,}")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= budget) {
                units.add(p);
                continue;
            }
            Matcher sentence = SENTENCE.matcher(p);
            while (sentence.find()) {
                String s = sentence.group().strip();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.length() <= budget) {
                    units.add(s);
                } else {
                    for (int i = 0; i < s.length(); i += budget) {
                        units.add(s.substring(i, Math.min(i + budget, s.length())));
                    }
                }
            }
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            boolean overflow = current.length() > 0 && current.length() + unit.length() + 1 > budget;
            if (overflow) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            parts.add(current.toString().strip());
        }
        return withOverlap(parts, budget);
    }

    /**
     * 给相邻块补重叠。只在"补完不超上限"时补，宁可少补也不让块膨胀。
     */
    private List<String> withOverlap(List<String> parts, int budget) {
        if (overlapChars == 0 || parts.size() < 2) {
            return parts;
        }
        List<String> result = new ArrayList<>(parts.size());
        result.add(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            String previous = parts.get(i - 1);
            String tail = previous.length() > overlapChars
                    ? previous.substring(previous.length() - overlapChars)
                    : previous;
            String candidate = tail + "\n" + parts.get(i);
            result.add(candidate.length() <= budget ? candidate : parts.get(i));
        }
        return result;
    }

    private static String compose(String headingPath, String body) {
        return headingPath.isEmpty() ? body : headingPath + "\n" + body;
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（本节较长，后续内容见子块）";
    }
}
