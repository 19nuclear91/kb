package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;
import com.zhishiku.kb.worker.parse.model.SourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 按字符窗口切分，同时尽量在段落或句子边界断开。
 */
public final class TextChunker {

    /**
     * 文本分块结果。
     *
     * @param content        分块正文
     * @param pageNo         首个来源块的页码
     * @param headingPath    首个来源块的标题路径
     * @param source         分块覆盖范围的来源坐标
     * @param structureTypes 分块覆盖到的结构类型
     * @param tableCells     分块覆盖到的表格单元格
     */
    public record Chunk(String content, Integer pageNo, String headingPath,
                        SourceLocation source,
                        List<DocumentBlock.Kind> structureTypes,
                        List<List<String>> tableCells) {
        public Chunk(String content, Integer pageNo, String headingPath) {
            this(content, pageNo, headingPath, SourceLocation.unknown());
        }

        public Chunk(String content, Integer pageNo, String headingPath,
                     SourceLocation source) {
            this(content, pageNo, headingPath, source,
                    List.of(DocumentBlock.Kind.UNKNOWN), List.of());
        }

        public Chunk {
            content = content == null ? "" : content;
            structureTypes = structureTypes == null || structureTypes.isEmpty()
                    ? List.of(DocumentBlock.Kind.UNKNOWN) : List.copyOf(structureTypes);
            tableCells = tableCells == null ? List.of() : tableCells.stream()
                    .map(row -> row == null ? List.<String>of() : row.stream()
                            .map(cell -> cell == null ? "" : cell).toList())
                    .toList();
            source = source == null ? SourceLocation.unknown() : source;
        }

        public DocumentBlock.Kind kind() {
            return structureTypes.size() == 1 ? structureTypes.get(0) : DocumentBlock.Kind.UNKNOWN;
        }
    }

    /**
     * 单个分块的目标字符数上限；小于 100 视为配置错误。
     */
    private final int chunkSize;
    /**
     * 相邻分块重叠的字符数，用于保住跨块的语义衔接。
     */
    private final int overlap;

    public TextChunker(int chunkSize, int overlap) {
        if (chunkSize < 100 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize/overlap 参数无效");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<Chunk> split(String rawText) {
        return split(ParsedDocument.fromPlainText("", "text/plain", rawText));
    }

    /**
     * 按统一中间模型分块，同时保留首个来源块的页码和标题路径。
     */
    public List<Chunk> split(ParsedDocument document) {
        if (document == null || document.blocks().isEmpty()) return List.of();
        Rendered rendered = render(document);
        // render() 已对每个结构块做过归一化；这里不能再次压缩空白，
        // 否则字符位置会与来源块的 span 错位。
        String text = rendered.text();
        if (text.isBlank()) return List.of();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int target = Math.min(text.length(), start + chunkSize);
            int end = target;
            if (target < text.length()) {
                int boundary = bestBoundary(text, start + chunkSize / 2, target);
                if (boundary > start) end = boundary;
            }
            String content = text.substring(start, end).trim();
            if (!content.isBlank()) {
                int contentStart = start + leadingWhitespace(text, start, end);
                int contentEnd = end - trailingWhitespace(text, contentStart, end);
                RenderedBlock first = rendered.find(contentStart);
                RenderedBlock last = rendered.find(Math.max(contentStart, contentEnd - 1));
                List<RenderedBlock> covered = rendered.covering(contentStart,
                        Math.max(contentStart + 1, contentEnd));
                List<DocumentBlock.Kind> structureTypes = covered.stream()
                        .map(RenderedBlock::kind)
                        .distinct().toList();
                List<List<String>> tableCells = covered.stream()
                        .flatMap(item -> item.tableCells().stream()).toList();
                result.add(new Chunk(content,
                        first == null ? null : first.block().pageNo(),
                        first == null ? null : first.block().headingPath(),
                        sourceRange(first, last), structureTypes, tableCells));
            }
            if (end >= text.length()) break;
            int next = Math.max(start + 1, end - overlap);
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            start = next;
        }
        return List.copyOf(result);
    }

    private Rendered render(ParsedDocument document) {
        StringBuilder text = new StringBuilder();
        List<RenderedBlock> blocks = new ArrayList<>();
        for (DocumentBlock block : document.blocks()) {
            String content = normalize(block.text());
            if (content.isBlank()) continue;
            List<String> segments = tableSegments(block, content);
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                String segment = segments.get(segmentIndex);
                if (segment.isBlank()) continue;
                if (!text.isEmpty()) text.append("\n\n");
                int start = text.length();
                text.append(segment);
                List<List<String>> cells = segments.size() > 1 && block.kind() == DocumentBlock.Kind.TABLE
                        ? List.of(block.tableCells().get(Math.min(segmentIndex,
                        block.tableCells().size() - 1)))
                        : block.tableCells();
                blocks.add(new RenderedBlock(start, text.length(), block, cells));
            }
        }
        return new Rendered(text.toString(), blocks);
    }

    private List<String> tableSegments(DocumentBlock block, String content) {
        if (block.kind() != DocumentBlock.Kind.TABLE || block.tableCells().size() < 2
                || content.length() <= chunkSize) return List.of(content);
        return block.tableCells().stream().map(row -> tableRowText(row)).toList();
    }

    private static String tableRowText(List<String> row) {
        return "| " + String.join(" | ", row) + " |";
    }

    private static SourceLocation sourceRange(RenderedBlock first, RenderedBlock last) {
        if (first == null) return SourceLocation.unknown();
        SourceLocation start = first.block().source();
        SourceLocation end = last == null ? start : last.block().source();
        return start.withEnd(end);
    }

    private static int leadingWhitespace(String text, int start, int end) {
        int index = start;
        while (index < end && Character.isWhitespace(text.charAt(index))) index++;
        return index - start;
    }

    private static int trailingWhitespace(String text, int start, int end) {
        int index = end;
        while (index > start && Character.isWhitespace(text.charAt(index - 1))) index--;
        return end - index;
    }

    private static int bestBoundary(String text, int from, int to) {
        int best = -1;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?') best = i + 1;
        }
        return best;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\u000B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .trim();
    }

    /**
     * 归一化后的全文与块位置映射。
     *
     * @param text   归一化后的全文
     * @param blocks 各文档块在全文中的字符区间
     */
    private record Rendered(String text, List<RenderedBlock> blocks) {
        private RenderedBlock find(int position) {
            return blocks.stream().filter(block -> position >= block.start()
                    && position < block.end()).findFirst().orElse(null);
        }

        private List<RenderedBlock> covering(int start, int end) {
            return blocks.stream().filter(block -> block.end() > start && block.start() < end).toList();
        }
    }

    /**
     * 单个文档块在全文中的落位。
     *
     * @param start      起始字符下标（含）
     * @param end        结束字符下标（不含）
     * @param block      原始文档块
     * @param tableCells 表格块渲染出的单元格；非表格块为空
     */
    private record RenderedBlock(int start, int end, DocumentBlock block,
                                 List<List<String>> tableCells) {
        private DocumentBlock.Kind kind() {
            return block.kind();
        }
    }
}
