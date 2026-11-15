package com.zhishiku.kb.app.document.stewardship.model;

public record StewardshipDocument(long documentId, long spaceId, String status, boolean spaceArchived) {
}
