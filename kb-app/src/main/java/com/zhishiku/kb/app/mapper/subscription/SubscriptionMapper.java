package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPeriod;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPreference;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 本人关系和频率写入，仅从已取得统一顺序的短业务事务调用。 */
@Mapper
public interface SubscriptionMapper {
    @Select("SELECT * FROM kb_knowledge_subscription WHERE user_id = #{userId} AND kind = #{kind} AND target_id = #{targetId}")
    KnowledgeSubscription findTarget(
            @Param("userId")
            long userId,
            @Param("kind")
            SubscriptionKind kind,
            @Param("targetId")
            long targetId
    );

    @Select("SELECT * FROM kb_knowledge_subscription WHERE id = #{id} AND user_id = #{userId}")
    KnowledgeSubscription find(
            @Param("userId")
            long userId,
            @Param("id")
            UUID id
    );

    @Select("SELECT count(*) FROM kb_knowledge_subscription WHERE user_id = #{userId} AND state = 'ACTIVE'")
    long activeCount(long userId);

    @Insert("""
            INSERT INTO kb_knowledge_subscription
                (id, user_id, kind, target_id, state, generation_id, state_version, created_at, updated_at)
            VALUES (#{id}, #{userId}, #{kind}, #{targetId}, #{state}, #{generationId}, #{stateVersion}, #{createdAt}, #{updatedAt})
            """)
    int insert(KnowledgeSubscription subscription);

    @Update("""
            UPDATE kb_knowledge_subscription SET state = #{state}, generation_id = #{generationId},
                state_version = #{stateVersion}, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId} AND state_version = #{stateVersion} - 1
            """)
    int advance(KnowledgeSubscription subscription);

    @Insert("""
            INSERT INTO kb_knowledge_subscription_period
                (id, subscription_id, user_id, start_position, start_at, end_position, end_at)
            VALUES (#{id}, #{subscriptionId}, #{userId}, #{startPosition}, #{startAt}, #{endPosition}, #{endAt})
            """)
    int insertPeriod(SubscriptionPeriod period);

    @Update("""
            UPDATE kb_knowledge_subscription_period SET end_position = #{position}, end_at = #{at}
            WHERE id = #{generationId} AND user_id = #{userId} AND end_position IS NULL
            """)
    int closePeriod(
            @Param("userId")
            long userId,
            @Param("generationId")
            UUID generationId,
            @Param("position")
            long position,
            @Param("at")
            Instant at
    );

    @Select("SELECT * FROM kb_knowledge_preference WHERE user_id = #{userId}")
    SubscriptionPreference preference(long userId);

    @Insert("""
            INSERT INTO kb_knowledge_preference(user_id, mode, version, effective_position, effective_at)
            VALUES (#{userId}, #{mode}, #{version}, #{effectivePosition}, #{effectiveAt})
            ON CONFLICT (user_id) DO UPDATE SET mode = EXCLUDED.mode, version = EXCLUDED.version,
                effective_position = EXCLUDED.effective_position, effective_at = EXCLUDED.effective_at
            WHERE kb_knowledge_preference.version = EXCLUDED.version - 1
            """)
    int savePreference(SubscriptionPreference preference);

    @Insert("""
            INSERT INTO kb_knowledge_preference_revision(user_id, position, mode, effective_at)
            VALUES (#{userId}, #{effectivePosition}, #{mode}, #{effectiveAt})
            """)
    int appendPreference(SubscriptionPreference preference);
}
