package com.zhishiku.kb.app.document.diff.model;

/** tableCellsJson 仅在受限计算线程中解析。 */
public record SnapshotBlock(int index, String kind, String text, String headingPath, Integer pageNo,
                            Integer lineStart, Integer lineEnd, Integer charStart, Integer charEnd,
                            String locator, String tableCellsJson) {
}
