package com.zhishiku.kb.app.collaboration.model;

import com.zhishiku.kb.common.review.ReviewCommandDomain;
import lombok.Data;

import java.util.UUID;

/** 仅含命令绑定和无敏感内容的成功回执，不保存载荷原文。 */
@Data
public class CommandReceiptRow {
    private ReviewCommandDomain operationDomain;
    private String requestId;
    private long actorUserId;
    private Long spaceId;
    private Long docId;
    private Long versionId;
    private UUID reviewId;
    private long expectedVersion;
    private String inputDigest;
    private String receipt;
}
