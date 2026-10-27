package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.review.ReviewState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.UUID;

/** 已在查询内完成权限过滤的有界投影，不包含私有意见。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewListRow extends ReviewVersionState {
    private UUID reviewId;
    private Integer roundNo;
    private ReviewState reviewState;
    private Instant submittedAt;
    private boolean canRead;
    private String title;
}
