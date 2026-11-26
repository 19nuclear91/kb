package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 原意图回执持久化，锁只限当前事务。 */
@Mapper
public interface AnswerCardCommandMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockRequest(
            @Param("key")
            String key
    );

    @Select("""
            SELECT operation, request_id, actor_id, space_id, card_id, branch_id, revision_id, review_id,
                expected_card_version, digest_version, input_digest, receipt::text AS receipt, created_at
            FROM kb_answer_card_command WHERE operation = #{operation} AND request_id = #{requestId}
            """)
    AnswerCardCommand find(
            @Param("operation")
            String operation,
            @Param("requestId")
            String requestId
    );

    @Insert("""
            INSERT INTO kb_answer_card_command
                (operation, request_id, actor_id, space_id, card_id, branch_id, revision_id, review_id,
                 expected_card_version, digest_version, input_digest, receipt)
            VALUES (#{operation}, #{requestId}, #{actorId}, #{spaceId}, #{cardId}, #{branchId}, #{revisionId},
                #{reviewId}, #{expectedCardVersion}, #{digestVersion}, #{inputDigest}, CAST(#{receipt} AS jsonb))
            """)
    int insert(AnswerCardCommand command);
}
