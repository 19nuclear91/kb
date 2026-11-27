package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.domain.subscription.SubscriptionCommand;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 本人命令锁与回执，数据库锁仅保持在当前业务事务内。 */
@Mapper
public interface SubscriptionCommandMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockRequest(String key);

    @Select("""
            SELECT actor_id, request_id, operation, input_digest, receipt::text AS receipt, created_at
            FROM kb_knowledge_command_receipt WHERE actor_id = #{actor} AND request_id = #{requestId}
            """)
    SubscriptionCommand find(
            @Param("actor")
            long actor,
            @Param("requestId")
            String requestId
    );

    @Insert("""
            INSERT INTO kb_knowledge_command_receipt(actor_id, request_id, operation, input_digest)
            VALUES (#{actorId}, #{requestId}, #{operation}, #{inputDigest})
            """)
    int insert(SubscriptionCommand command);

    @Update("""
            UPDATE kb_knowledge_command_receipt SET receipt = CAST(#{receipt} AS jsonb)
            WHERE actor_id = #{actorId} AND request_id = #{requestId} AND operation = #{operation}
                AND input_digest = #{inputDigest} AND receipt IS NULL
            """)
    int complete(SubscriptionCommand command);
}
