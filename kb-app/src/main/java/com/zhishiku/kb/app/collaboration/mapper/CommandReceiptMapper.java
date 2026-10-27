package com.zhishiku.kb.app.collaboration.mapper;

import com.zhishiku.kb.app.collaboration.model.CommandReceiptRow;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommandReceiptMapper {
    @Insert("""
            INSERT INTO kb_review_command_receipt
              (operation_domain, request_id, actor_user_id, space_id, doc_id, version_id,
               review_id, expected_version, input_digest)
            VALUES (#{domain}, #{requestId}, #{actorUserId}, #{spaceId}, #{docId}, #{versionId},
                    #{reviewId}, #{expectedVersion}, #{inputDigest})
            ON CONFLICT (operation_domain, request_id) DO NOTHING
            """)
    int claim(ReviewCommandBinding binding);

    @Select("""
            SELECT operation_domain, request_id, actor_user_id, space_id, doc_id, version_id,
                   review_id, expected_version, input_digest, receipt::text AS receipt
            FROM kb_review_command_receipt
            WHERE operation_domain = #{domain} AND request_id = #{requestId}
            """)
    CommandReceiptRow find(ReviewCommandBinding binding);

    @Update("""
            UPDATE kb_review_command_receipt SET receipt = CAST(#{receipt} AS jsonb)
            WHERE operation_domain = #{binding.domain} AND request_id = #{binding.requestId}
              AND actor_user_id = #{binding.actorUserId} AND input_digest = #{binding.inputDigest}
              AND receipt IS NULL
            """)
    int complete(@Param("binding") ReviewCommandBinding binding, @Param("receipt") String receipt);
}
