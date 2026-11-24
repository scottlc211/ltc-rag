package com.ltc.rag.chunk;

import com.ltc.rag.common.Digests;
import com.ltc.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题感知 + 表格/代码块原子保护 + 父子块切分器（v2-Lite）。
 *
 * <p>核心能力：
 * <ol>
 *   <li><b>标题感知</b>：按 Markdown 标题栈生成 {@code headingPath}，代码块内的 {@code #} 不会被误判为标题；</li>
 *   <li><b>表格表头跟随（Header Propagation）</b>：长表格按行切分时，每个子块自动带上原表前两行（表头 + 分隔线），
 *       避免下半截表格丢失列含义；</li>
 *   <li><b>代码块围栏自愈（Fence Healing）</b>：长代码块按空行/行切分时，每段自动补齐开合围栏（含语言标识），
 *       避免语法与前端渲染破损；</li>
 *   <li><b>块哈希 + 页码映射</b>：为每个分块计算 {@code contentHash}（增量复用向量）与 {@code pageNo}（PDF 引用溯源）。</li>
 * </ol>
 */
@Component
public class Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;\\n]?");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?[\\s:-]+\\|[\\s|:-]*$");

    private final int maxChildChars;
    private final int overlapChars;
    private final int maxParentChars;

    public Chunker(RagProperties props) {
        this.maxChildChars = Math.max(120, props.chunking().maxChildChars());
        this.overlapChars = Math.max(0, Math.min(props.chunking().overlapChars(), maxChildChars / 2));
        this.maxParentChars = Math.max(maxChildChars, props.chunking().maxParentChars());
    }

    public List<ChunkPiece> split(String markdown) {
        return split(markdown, List.of());
    }

    public List<ChunkPiece> split(String markdown, List<Integer> pageOffsets) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<ChunkPiece> pieces = new ArrayList<>();
        int index = 0;

        for (Section section : readSections(markdown)) {
            String body = section.body().strip();
            if (body.isEmpty()) {
                continue;
            }
            String heading = section.headingPath();
            int budget = budgetFor(heading);
            List<AtomicBlock> blocks = splitAtomicBlocks(body);

            // 场景 1：短章节且只有单个原子块，直接作为一个叶子块，不生成冗余父块
            if (blocks.size() == 1 && body.length() <= budget) {
                AtomicBlock only = blocks.get(0);
                String blockText = compose(heading, only.content());
                String chunkType = chunkTypeFor(only.kind());
                Integer page = resolvePage(section.startOffset() + only.relOffset(), pageOffsets);
                pieces.add(buildPiece(chunkType, index++, null, heading, blockText, page));
                continue;
            }

            // 场景 2：长章节或包含多个混合原子块（文字 + 表格/代码）
            // 先落一个父块承载整节上下文，再将各原子块拆为精准叶子块
            Integer sectionPage = resolvePage(section.startOffset(), pageOffsets);
            int parentIndex = index;
            String parentText = compose(heading, truncate(body, maxParentChars));
            pieces.add(buildPiece(ChunkPiece.PARENT, index++, null, heading, parentText, sectionPage));

            for (AtomicBlock block : blocks) {
                Integer blockPage = resolvePage(section.startOffset() + block.relOffset(), pageOffsets);
                switch (block.kind()) {
                    case TABLE -> {
                        for (String part : splitTable(block.content(), budget)) {
                            pieces.add(buildPiece(ChunkPiece.TABLE, index++, parentIndex, heading,
                                    compose(heading, part), blockPage));
                        }
                    }
                    case CODE -> {
                        for (String part : splitCode(block.content(), budget)) {
                            pieces.add(buildPiece(ChunkPiece.CODE, index++, parentIndex, heading,
                                    compose(heading, part), blockPage));
                        }
                    }
                    case TEXT -> {
                        for (String part : splitLong(block.content(), budget)) {
                            pieces.add(buildPiece(ChunkPiece.CHILD, index++, parentIndex, heading,
                                    compose(heading, part), blockPage));
                        }
                    }
                }
            }
        }
        return pieces;
    }

    private static ChunkPiece buildPiece(String type, int index, Integer parentIndex,
                                         String heading, String content, Integer pageNo) {
        return new ChunkPiece(
                type,
                index,
                parentIndex,
                heading,
                content,
                content.length(),
                Digests.sha256Hex(content),
                pageNo);
    }

    private enum BlockKind {
        TEXT, CODE, TABLE
    }

    private record Section(String headingPath, String body, int startOffset) {
    }

    private record AtomicBlock(BlockKind kind, String content, int relOffset) {
    }

    private static String chunkTypeFor(BlockKind kind) {
        return switch (kind) {
            case TABLE -> ChunkPiece.TABLE;
            case CODE -> ChunkPiece.CODE;
            case TEXT -> ChunkPiece.CHILD;
        };
    }

    /**
     * 按标题把 Markdown 切成小节，维护标题栈并记录每节在原文中的起始字符偏移。
     */
    private static List<Section> readSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();
        String currentPath = "";
        int sectionStartOffset = 0;
        int offset = 0;
        String activeFenceMarker = null;

        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineLen = line.length() + (i < lines.length - 1 ? 1 : 0);

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (activeFenceMarker == null) {
                    activeFenceMarker = marker;
                } else if (marker.charAt(0) == activeFenceMarker.charAt(0) && marker.length() >= activeFenceMarker.length()) {
                    activeFenceMarker = null;
                }
                if (body.isEmpty()) {
                    sectionStartOffset = offset;
                }
                body.append(line).append('\n');
                offset += lineLen;
                continue;
            }

            Matcher heading = activeFenceMarker != null ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                flushSection(sections, currentPath, body, sectionStartOffset);
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                while (stack.size() >= level) {
                    stack.removeLast();
                }
                stack.addLast(title);
                currentPath = String.join(" > ", stack);
                sectionStartOffset = offset + lineLen;
            } else {
                if (body.isEmpty() && !line.isBlank()) {
                    sectionStartOffset = offset;
                }
                body.append(line).append('\n');
            }
            offset += lineLen;
        }
        flushSection(sections, currentPath, body, sectionStartOffset);
        return sections;
    }

    private static void flushSection(List<Section> sections, String headingPath, StringBuilder body, int startOffset) {
        String text = body.toString();
        if (!text.isBlank()) {
            sections.add(new Section(headingPath, text, startOffset));
        }
        body.setLength(0);
    }

    /**
     * 将单个章节正文拆分为有序的原子块列表（TEXT / CODE / TABLE）。
     */
    private static List<AtomicBlock> splitAtomicBlocks(String body) {
        List<AtomicBlock> blocks = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        StringBuilder textBuf = new StringBuilder();
        int textStart = 0;
        int offset = 0;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            int lineLen = line.length() + (i < lines.length - 1 ? 1 : 0);

            // 1) 检测代码块起始围栏
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushTextBlock(blocks, textBuf, textStart);
                String marker = fence.group(1);
                int codeStart = offset;
                StringBuilder codeBuf = new StringBuilder(line).append('\n');
                offset += lineLen;
                i++;
                while (i < lines.length) {
                    String next = lines[i];
                    int nextLen = next.length() + (i < lines.length - 1 ? 1 : 0);
                    codeBuf.append(next).append('\n');
                    offset += nextLen;
                    i++;
                    Matcher close = FENCE.matcher(next);
                    if (close.matches()
                            && close.group(1).charAt(0) == marker.charAt(0)
                            && close.group(1).length() >= marker.length()
                            && close.group(2).isBlank()) {
                        break;
                    }
                }
                blocks.add(new AtomicBlock(BlockKind.CODE, codeBuf.toString().strip(), codeStart));
                textStart = offset;
                continue;
            }

            // 2) 检测 Markdown 表格（当前行含 | 且下一行是 |---| 分隔行）
            if (i + 1 < lines.length && isTableRow(line) && isTableSeparator(lines[i + 1])) {
                flushTextBlock(blocks, textBuf, textStart);
                int tableStart = offset;
                StringBuilder tableBuf = new StringBuilder();
                tableBuf.append(line).append('\n');
                offset += lineLen;
                i++;
                // 加入分隔行
                String sepLine = lines[i];
                int sepLen = sepLine.length() + (i < lines.length - 1 ? 1 : 0);
                tableBuf.append(sepLine).append('\n');
                offset += sepLen;
                i++;
                // 持续收集数据行
                while (i < lines.length && isTableRow(lines[i])) {
                    String rowLine = lines[i];
                    int rowLen = rowLine.length() + (i < lines.length - 1 ? 1 : 0);
                    tableBuf.append(rowLine).append('\n');
                    offset += rowLen;
                    i++;
                }
                blocks.add(new AtomicBlock(BlockKind.TABLE, tableBuf.toString().strip(), tableStart));
                textStart = offset;
                continue;
            }

            // 3) 普通文本行
            if (textBuf.isEmpty() && !line.isBlank()) {
                textStart = offset;
            }
            textBuf.append(line).append('\n');
            offset += lineLen;
            i++;
        }
        flushTextBlock(blocks, textBuf, textStart);
        return blocks;
    }

    private static void flushTextBlock(List<AtomicBlock> blocks, StringBuilder buf, int startOffset) {
        String text = buf.toString().strip();
        if (!text.isEmpty()) {
            blocks.add(new AtomicBlock(BlockKind.TEXT, text, startOffset));
        }
        buf.setLength(0);
    }

    private static boolean isTableRow(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.strip();
        return trimmed.contains("|") && (trimmed.startsWith("|") || trimmed.split("\\|").length >= 2);
    }

    private static boolean isTableSeparator(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.strip();
        return trimmed.contains("-") && trimmed.contains("|") && TABLE_SEP.matcher(trimmed).matches();
    }

    /**
     * 表格切分（表头自动跟随）：整表不超预算就不拆；超预算时按行拆分，每块都冠以原表前两行（表头 + 分隔线）。
     */
    private List<String> splitTable(String tableText, int budget) {
        if (tableText.length() <= budget) {
            return List.of(tableText);
        }
        String[] lines = tableText.split("\n");
        if (lines.length <= 2) {
            return List.of(tableText);
        }
        String header = lines[0] + "\n" + lines[1];
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int rowsInCurrent = 0;

        for (int i = 2; i < lines.length; i++) {
            String row = lines[i];
            boolean overflow = rowsInCurrent > 0 && current.length() + row.length() + 1 > budget;
            if (overflow) {
                parts.add(current.toString().strip());
                current = new StringBuilder(header);
                rowsInCurrent = 0;
            }
            current.append('\n').append(row);
            rowsInCurrent++;
        }
        if (rowsInCurrent > 0) {
            parts.add(current.toString().strip());
        }
        return parts.isEmpty() ? List.of(tableText) : parts;
    }

    /**
     * 代码块切分（围栏自愈）：整块不超预算就不拆；超预算时剥离首尾围栏，按空行/单行拆分并每段补齐围栏。
     */
    private List<String> splitCode(String codeText, int budget) {
        if (codeText.length() <= budget) {
            return List.of(codeText);
        }
        String[] lines = codeText.split("\n", -1);
        if (lines.length <= 2) {
            return List.of(codeText);
        }
        String openFence = lines[0].stripTrailing();
        Matcher openMatcher = FENCE.matcher(openFence);
        String fenceMark = openMatcher.matches() ? openMatcher.group(1) : "```";
        String lastLine = lines[lines.length - 1].strip();
        boolean hasClose = FENCE.matcher(lastLine).matches() && lastLine.startsWith(fenceMark.substring(0, 1));
        String closeFence = hasClose ? lastLine : fenceMark;

        int endIdx = hasClose ? lines.length - 1 : lines.length;
        int innerBudget = Math.max(80, budget - openFence.length() - closeFence.length() - 2);

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 1; i < endIdx; i++) {
            String line = lines[i];
            if (current.length() > 0 && current.length() + line.length() + 1 > innerBudget) {
                parts.add(wrapFence(openFence, current.toString().stripTrailing(), closeFence));
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            // 单行极长（如压缩 JSON）直接截段，防止单块无限膨胀
            if (line.length() > innerBudget && current.isEmpty()) {
                for (int s = 0; s < line.length(); s += innerBudget) {
                    parts.add(wrapFence(openFence, line.substring(s, Math.min(s + innerBudget, line.length())), closeFence));
                }
            } else {
                current.append(line);
            }
        }
        if (current.length() > 0) {
            parts.add(wrapFence(openFence, current.toString().stripTrailing(), closeFence));
        }
        return parts.isEmpty() ? List.of(codeText) : parts;
    }

    private static String wrapFence(String openFence, String inner, String closeFence) {
        return openFence + "\n" + inner + "\n" + closeFence;
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

    /**
     * 根据字符偏移量反查 PDF 页码（1-based）；非 PDF 文档（pageOffsets 为空）返回 null。
     */
    private static Integer resolvePage(int charOffset, List<Integer> pageOffsets) {
        if (pageOffsets == null || pageOffsets.isEmpty()) {
            return null;
        }
        int page = 1;
        for (int i = 0; i < pageOffsets.size(); i++) {
            if (pageOffsets.get(i) <= charOffset) {
                page = i + 1;
            } else {
                break;
            }
        }
        return page;
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
