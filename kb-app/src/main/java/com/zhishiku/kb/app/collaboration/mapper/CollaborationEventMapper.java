package com.zhishiku.kb.app.collaboration.mapper;

import com.zhishiku.kb.common.review.CollaborationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface CollaborationEventMapper {
    @Insert("""
            INSERT INTO kb_collaboration_event (event_id, protocol_version, kind, business_key, doc_id, version_id,
                review_id, round_no, actor_user_id, occurred_at, submitted_at, decided_at, comment_id, root_comment_id)
            VALUES (#{eventId}, 1, #{kind}, #{businessKey}, #{documentId}, #{versionId}, #{reviewId}, #{roundNo},
                #{actorUserId}, #{occurredAt}, #{submittedAt}, #{decidedAt}, #{commentId}, #{rootCommentId})
            ON CONFLICT (business_key) DO NOTHING
            """)
    int append(CollaborationEvent event);

    @Insert("INSERT INTO kb_collaboration_event_recipient (event_id, user_id) VALUES (#{eventId}, #{userId}) ON CONFLICT DO NOTHING")
    int recipient(@Param("eventId") UUID eventId, @Param("userId") long userId);

    @Insert("INSERT INTO kb_collaboration_coverage (domain, protocol_version, started_at) VALUES ('REVIEW', 1, #{now}) ON CONFLICT DO NOTHING")
    int startCoverage(@Param("now") Instant now);

    /** 评论覆盖起点只由首个新版评论事实在同一事务写入，不回填历史评论。 */
    @Insert("INSERT INTO kb_collaboration_coverage (domain, protocol_version, started_at) VALUES ('COMMENT', 1, #{now}) ON CONFLICT DO NOTHING")
    int startCommentCoverage(@Param("now") Instant now);
}
