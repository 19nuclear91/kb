package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.document.mapper.DocumentReviewEligibilityMapper;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import org.springframework.stereotype.Service;

import java.util.List;

/** 每次请求重新读取；名单仅供审核事务与无正文提醒意图使用，不作为阅读授权快照。 */
@Service
public class ReviewEligibilityService {
    private final DocumentReviewEligibilityMapper mapper;

    public ReviewEligibilityService(DocumentReviewEligibilityMapper mapper) {
        this.mapper = mapper;
    }

    public List<Long> requirePool(long documentId, Long uploader, long submittedBy) {
        var rows = mapper.candidates(documentId, uploader, submittedBy, null);
        if (rows == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        var users = rows.stream().filter(row -> row.eligible(uploader, submittedBy))
                .map(row -> row.userId()).distinct().sorted().toList();
        if (users.isEmpty()) throw new ReviewFailure(ReviewError.NO_ELIGIBLE_REVIEWER);
        return users;
    }

    public boolean eligible(long userId, long documentId, Long uploader, Long submittedBy) {
        var rows = mapper.candidates(documentId, uploader, submittedBy, userId);
        if (rows == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        return rows.stream().anyMatch(row -> row.userId() == userId && row.eligible(uploader, submittedBy));
    }
}
