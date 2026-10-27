package com.zhishiku.kb.worker.parse.model;

import java.util.List;

/**
 * 统一解析中间模型中的一个结构块。
 *
 * <p>解析器可以保留表格单元格和来源坐标，分块器只依赖这里的稳定字段，
 * 不需要知道第三方解析服务的私有对象结构。
 *
 * @param kind        结构块类型
 * @param text        结构块正文
 * @param pageNo      来源页码
 * @param headingPath 当前标题路径
 * @param source      来源坐标
 * @param tableCells  表格单元格；非表格块为空
 */
public record DocumentBlock(
        Kind kind,
        String text,
        Integer pageNo,
        String headingPath,
        SourceLocation source,
        List<List<String>> tableCells) {

    public enum Kind {PARAGRAPH, HEADING, LIST_ITEM, TABLE, CODE, UNKNOWN}

    public DocumentBlock {
        if (kind == null) kind = Kind.UNKNOWN;
        if (text == null) text = "";
        if (headingPath != null && headingPath.isBlank()) headingPath = null;
        if (source == null) source = SourceLocation.unknown();
        tableCells = tableCells == null ? List.of() : tableCells.stream()
                .map(row -> row == null ? List.<String>of() : row.stream()
                        .map(cell -> cell == null ? "" : cell).toList())
                .toList();
    }

    public DocumentBlock(Kind kind, String text, Integer pageNo,
                         String headingPath, SourceLocation source) {
        this(kind, text, pageNo, headingPath, source, List.of());
    }

    public boolean hasContent() {
        return !text.isBlank();
    }
}
