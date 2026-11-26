package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.answercard.AnswerCardBranch;
import com.zhishiku.kb.app.domain.answercard.AnswerCardConfirmation;
import com.zhishiku.kb.app.domain.answercard.AnswerCardRevision;
import com.zhishiku.kb.app.domain.answercard.AnswerCardReview;
import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardStewardship;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceLocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;

/** 卡片持久化操作，所有写入由短业务事务调用。 */
@Mapper
public interface AnswerCardMapper {
    OffsetDateTime now();
    void limits();
    long nextCardId();
    long nextBranchId();
    long nextRevisionId();
    long nextReviewId();
    AnswerCard card(long id);
    AnswerCard lockCard(long id);
    Long lockBranch(long id);
    Long lockReview(long cardId);
    Long lockStewardship(long cardId);
    AnswerCardBranch branch(long id);
    AnswerCardRevision revision(long id);
    AnswerCardReview review(long id);
    AnswerCardReview activeReview(long cardId);
    boolean hasActiveReview(long cardId);
    AnswerCardReview latestReview(long revisionId);
    AnswerCardStewardship stewardship(long cardId);
    List<AnswerCardSource> sources(long revisionId);
    List<Long> contributors(long branchId);
    List<AnswerCardSourceLocation> sourceLocations(List<Long> documentIds);
    Long shareTree(long spaceId);
    Long shareDocument(long documentId);
    Long shareVersion(long versionId);
    long nextRevisionNo(long cardId);
    long nextBranchNo(long cardId);
    long nextRoundNo(long cardId);
    int insertCard(AnswerCard card);
    int insertBranch(AnswerCardBranch branch);
    int insertRevision(AnswerCardRevision revision);
    int insertSource(AnswerCardSource source);
    int insertReview(AnswerCardReview review);
    int insertStewardship(AnswerCardStewardship stewardship);
    int updateStewardship(AnswerCardStewardship stewardship);
    int insertConfirmation(AnswerCardConfirmation confirmation);

    int contribute(
            @Param("branchId")
            long branchId,
            @Param("userId")
            long userId
    );

    int advanceBranch(
            @Param("id")
            long id,
            @Param("revisionId")
            long revisionId
    );

    int advanceCard(
            @Param("id")
            long id,
            @Param("expected")
            long expected,
            @Param("publishedRevisionId")
            Long publishedRevisionId,
            @Param("activeBranchId")
            Long activeBranchId
    );

    int closeBranch(long id);

    int decide(
            @Param("id")
            long id,
            @Param("expected")
            long expected,
            @Param("state")
            String state,
            @Param("actor")
            long actor,
            @Param("at")
            OffsetDateTime at,
            @Param("reason")
            String reason
    );
}
