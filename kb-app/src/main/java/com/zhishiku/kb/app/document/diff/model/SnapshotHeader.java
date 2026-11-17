package com.zhishiku.kb.app.document.diff.model;

public record SnapshotHeader(long versionId, int versionNo, String parserName, String parserVersion,
                             boolean truncated, int blockCount, long charCount, boolean available) {
}
