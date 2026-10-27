package com.zhishiku.kb.worker.parse.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析器输出的统一中间文档模型。
 *
 * <p>该模型刻意不携带第三方解析服务的私有类型，后续分块、引用和预览转换
 * 只消费结构块、来源坐标和解析问题。
 *
 * @param sourceName    原始文件名
 * @param mimeType      原始文件 MIME 类型
 * @param blocks        结构化解析块
 * @param issues        解析过程中的问题列表
 * @param truncated     是否因配置上限截断
 * @param parserName    实际解析器标识
 * @param parserVersion 实际解析器版本
 */
public record ParsedDocument(
        String sourceName,
        String mimeType,
        List<DocumentBlock> blocks,
        List<ParseIssue> issues,
        boolean truncated,
        String parserName,
        String parserVersion) {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+]\\s+|\\d+[.)]\\s+)(.+)$");

    public ParsedDocument {
        sourceName = sourceName == null ? "" : sourceName;
        mimeType = mimeType == null ? "" : mimeType;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        issues = issues == null ? List.of() : List.copyOf(issues);
        parserName = parserName == null || parserName.isBlank() ? "unknown" : parserName.trim();
        parserVersion = parserVersion == null || parserVersion.isBlank()
                ? "unknown" : parserVersion.trim();
    }

    /**
     * 保留旧的五参数构造方式，兼容已有解析器和测试夹具。
     */
    public ParsedDocument(String sourceName, String mimeType, List<DocumentBlock> blocks,
                          List<ParseIssue> issues, boolean truncated) {
        this(sourceName, mimeType, blocks, issues, truncated, "legacy-text", "1");
    }

    /**
     * 返回带有实际解析器身份的结果副本，不改变结构块和问题内容。
     */
    public ParsedDocument withParser(String name, String version) {
        return new ParsedDocument(sourceName, mimeType, blocks, issues, truncated,
                name, version);
    }

    /**
     * 追加一个解析问题，供兼容适配器把空结果转换为可诊断失败。
     */
    public ParsedDocument withIssue(ParseIssue issue) {
        if (issue == null) return this;
        List<ParseIssue> next = new ArrayList<>(issues);
        next.add(issue);
        return new ParsedDocument(sourceName, mimeType, blocks, next, truncated,
                parserName, parserVersion);
    }

    /**
     * 将旧的纯文本解析器结果升级为结构化块，供兼容适配器使用。
     */
    public static ParsedDocument fromPlainText(String sourceName, String mimeType, String rawText) {
        return fromPlainText(sourceName, mimeType, rawText, false);
    }

    public static ParsedDocument fromPlainText(String sourceName, String mimeType,
                                               String rawText, boolean truncated) {
        String text = rawText == null ? "" : rawText.replace("\u0000", "")
                .replace("\r\n", "\n").replace('\r', '\n');
        boolean hasPageBreak = text.indexOf('\f') >= 0;
        String[] lines = text.split("\\n", -1);
        List<DocumentBlock> blocks = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int paragraphStartLine = 0;
        int paragraphStartChar = 0;
        int offset = 0;
        int page = 1;
        boolean pageKnown = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineStart = offset;
            offset += line.length() + (i < lines.length - 1 ? 1 : 0);
            String[] pageParts = line.split("\\f", -1);
            for (int partIndex = 0; partIndex < pageParts.length; partIndex++) {
                String part = pageParts[partIndex];
                String trimmed = part.trim();
                int partStart = lineStart + line.indexOf(part);
                if (trimmed.isBlank()) {
                    flushParagraph(blocks, paragraph, paragraphStartLine, i + 1,
                            paragraphStartChar, partStart, pageKnown ? page : null,
                            currentHeadingPath(headings));
                    paragraphStartLine = 0;
                } else {
                    Matcher heading = MARKDOWN_HEADING.matcher(trimmed);
                    Matcher listItem = LIST_ITEM.matcher(trimmed);
                    if (heading.matches()) {
                        flushParagraph(blocks, paragraph, paragraphStartLine, i + 1,
                                paragraphStartChar, partStart, pageKnown ? page : null,
                                currentHeadingPath(headings));
                        paragraphStartLine = 0;
                        int level = heading.group(1).length();
                        while (headings.size() >= level) headings.remove(headings.size() - 1);
                        headings.add(heading.group(2).trim());
                        String path = currentHeadingPath(headings);
                        blocks.add(new DocumentBlock(DocumentBlock.Kind.HEADING,
                                heading.group(2).trim(), pageKnown ? page : null, path,
                                new SourceLocation(pageKnown ? page : null, i + 1, i + 1,
                                        partStart, partStart + part.length(), null)));
                    } else if (isTableLine(trimmed)) {
                        flushParagraph(blocks, paragraph, paragraphStartLine, i + 1,
                                paragraphStartChar, partStart, pageKnown ? page : null,
                                currentHeadingPath(headings));
                        paragraphStartLine = 0;
                        List<String> cells = Arrays.stream(trimmed.split("\\|", -1))
                                .map(String::trim).filter(cell -> !cell.isEmpty()).toList();
                        if (!isTableDivider(cells)) {
                            blocks.add(new DocumentBlock(DocumentBlock.Kind.TABLE, trimmed,
                                    pageKnown ? page : null, currentHeadingPath(headings),
                                    new SourceLocation(pageKnown ? page : null, i + 1, i + 1,
                                            partStart, partStart + part.length(), null),
                                    List.of(cells)));
                        }
                    } else if (listItem.matches()) {
                        flushParagraph(blocks, paragraph, paragraphStartLine, i + 1,
                                paragraphStartChar, partStart, pageKnown ? page : null,
                                currentHeadingPath(headings));
                        paragraphStartLine = 0;
                        blocks.add(new DocumentBlock(DocumentBlock.Kind.LIST_ITEM,
                                listItem.group(1).trim(), pageKnown ? page : null,
                                currentHeadingPath(headings),
                                new SourceLocation(pageKnown ? page : null, i + 1, i + 1,
                                        partStart, partStart + part.length(), null)));
                    } else {
                        if (paragraph.isEmpty()) {
                            paragraphStartLine = i + 1;
                            paragraphStartChar = partStart;
                        } else {
                            paragraph.append('\n');
                        }
                        paragraph.append(trimmed);
                    }
                }
                if (partIndex < pageParts.length - 1) {
                    flushParagraph(blocks, paragraph, paragraphStartLine, i + 1,
                            paragraphStartChar, partStart + part.length(),
                            pageKnown ? page : null, currentHeadingPath(headings));
                    paragraphStartLine = 0;
                    page++;
                    pageKnown = true;
                }
            }
        }
        flushParagraph(blocks, paragraph, paragraphStartLine, lines.length,
                paragraphStartChar, text.length(), pageKnown ? page : null,
                currentHeadingPath(headings));
        if (truncated) {
            issues.add(new ParseIssue(ParseIssue.Severity.WARNING, "TEXT_TRUNCATED",
                    "抽取文本达到配置上限，文档尾部可能未被解析", SourceLocation.unknown()));
        }
        return new ParsedDocument(sourceName, mimeType, mergeAdjacentTables(blocks), issues,
                truncated, "plain-text-heuristic", "1");
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == ParseIssue.Severity.ERROR);
    }

    public String plainText() {
        return blocks.stream().map(DocumentBlock::text)
                .filter(text -> !text.isBlank()).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static void flushParagraph(List<DocumentBlock> blocks, StringBuilder paragraph,
                                       int lineStart, int lineEnd, int charStart, int charEnd,
                                       Integer pageNo, String headingPath) {
        if (paragraph.isEmpty()) return;
        String text = paragraph.toString().trim();
        if (!text.isBlank()) {
            blocks.add(new DocumentBlock(DocumentBlock.Kind.PARAGRAPH, text, pageNo,
                    headingPath, new SourceLocation(pageNo, lineStart, lineEnd,
                    charStart, charEnd, null)));
        }
        paragraph.setLength(0);
    }

    private static String currentHeadingPath(List<String> headings) {
        return headings.isEmpty() ? null : String.join(" > ", headings);
    }

    private static List<DocumentBlock> mergeAdjacentTables(List<DocumentBlock> blocks) {
        List<DocumentBlock> merged = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            if (!merged.isEmpty() && block.kind() == DocumentBlock.Kind.TABLE) {
                DocumentBlock previous = merged.get(merged.size() - 1);
                if (previous.kind() == DocumentBlock.Kind.TABLE
                        && Objects.equals(previous.pageNo(), block.pageNo())
                        && Objects.equals(previous.headingPath(), block.headingPath())) {
                    List<List<String>> rows = new ArrayList<>(previous.tableCells());
                    rows.addAll(block.tableCells());
                    merged.set(merged.size() - 1, new DocumentBlock(
                            DocumentBlock.Kind.TABLE,
                            previous.text() + "\n" + block.text(),
                            previous.pageNo(), previous.headingPath(),
                            previous.source().withEnd(block.source()), rows));
                    continue;
                }
            }
            merged.add(block);
        }
        return List.copyOf(merged);
    }

    private static boolean isTableLine(String line) {
        return line.chars().filter(value -> value == '|').count() >= 2;
    }

    private static boolean isTableDivider(List<String> cells) {
        return !cells.isEmpty() && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }
}
