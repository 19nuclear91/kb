package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardPerson;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 有界查询在数据库截取前执行当前卡片与全部来源授权。 */
@Mapper
public interface AnswerCardQueryMapper {
    List<Long> browse(AnswerCardQuery query);
    List<Long> search(AnswerCardQuery query);
    List<Long> workspace(AnswerCardQuery query);
    List<Long> history(AnswerCardQuery query);
    List<Long> reviewHistory(AnswerCardQuery query);
    List<Long> management(AnswerCardQuery query);
    boolean displayable(AnswerCardQuery query);

    boolean eligible(
            @Param("cardId")
            long cardId,
            @Param("revisionId")
            long revisionId,
            @Param("userId")
            long userId,
            @Param("reviewer")
            boolean reviewer
    );

    boolean hasReviewer(
            @Param("cardId")
            long cardId,
            @Param("revisionId")
            long revisionId,
            @Param("branchId")
            long branchId,
            @Param("submittedBy")
            long submittedBy
    );

    List<AnswerCardPerson> candidates(
            @Param("cardId")
            long cardId,
            @Param("revisionId")
            long revisionId,
            @Param("namePattern")
            String namePattern,
            @Param("afterId")
            Long afterId,
            @Param("limit")
            int limit
    );

    AnswerCardPerson responsible(long cardId);
}
