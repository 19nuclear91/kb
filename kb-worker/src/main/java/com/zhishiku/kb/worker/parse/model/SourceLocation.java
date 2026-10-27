package com.zhishiku.kb.worker.parse.model;

import java.util.Objects;

/**
 * 解析器无关的来源坐标；未知维度保持为 {@code null}。
 *
 * @param pageNo    页码
 * @param lineStart 起始行号
 * @param lineEnd   结束行号
 * @param charStart 起始字符位置
 * @param charEnd   结束字符位置
 * @param locator   解析器提供的定位标识
 */
public record SourceLocation(
        Integer pageNo,
        Integer lineStart,
        Integer lineEnd,
        Integer charStart,
        Integer charEnd,
        String locator) {

    public SourceLocation {
        if (lineStart != null && lineStart <= 0) lineStart = null;
        if (lineEnd != null && lineEnd <= 0) lineEnd = null;
        if (charStart != null && charStart < 0) charStart = null;
        if (charEnd != null && charEnd < 0) charEnd = null;
        if (locator != null && locator.isBlank()) locator = null;
        if (locator != null && locator.length() > 1024) locator = locator.substring(0, 1024);
    }

    public static SourceLocation unknown() {
        return new SourceLocation(null, null, null, null, null, null);
    }

    public SourceLocation withEnd(SourceLocation end) {
        if (end == null) return this;
        Integer mergedPage = Objects.equals(pageNo, end.pageNo()) ? pageNo : null;
        return new SourceLocation(mergedPage,
                lineStart != null ? lineStart : end.lineStart(),
                end.lineEnd() != null ? end.lineEnd() : lineEnd,
                charStart != null ? charStart : end.charStart(),
                end.charEnd() != null ? end.charEnd() : charEnd,
                locator != null ? locator : end.locator());
    }
}
