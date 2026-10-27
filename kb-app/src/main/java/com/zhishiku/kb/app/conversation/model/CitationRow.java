package com.zhishiku.kb.app.conversation.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * 会话持久化字段；禁止自动输出正文和资格。
 */
@Getter
@Setter
public class CitationRow {
    private UUID turnId;
    private int ordinal;
    private long documentId;
    private long versionId;
    private long chunkId;
    private String title;
    private Integer versionNo;
    private Integer pageNo;
    private String headingPath;
    private String structureType;
    private String sourceLocator;
    private Integer sourceLineStart;
    private Integer sourceLineEnd;
    private Integer sourceCharStart;
    private Integer sourceCharEnd;
    private boolean contextTruncated;
}

