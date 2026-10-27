package com.zhishiku.kb.app.conversation.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * 会话持久化字段；禁止自动输出正文和资格。
 */
@Getter
@Setter
public class BatchRow {
    private UUID turnId;
    private long batchSeq;
    private String digest;
    private int codepoints;
}

