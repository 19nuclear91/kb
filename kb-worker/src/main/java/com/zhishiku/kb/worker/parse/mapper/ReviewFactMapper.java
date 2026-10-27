package com.zhishiku.kb.worker.parse.mapper;

import com.zhishiku.kb.common.review.CollaborationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** Worker 自有持久层，不依赖 app 或通知模块。 */
@Mapper
public interface ReviewFactMapper {
    @Insert("INSERT INTO kb_collaboration_coverage (domain, protocol_version, started_at) VALUES ('REVIEW', 1, #{now}) ON CONFLICT DO NOTHING")
    int startCoverage(@Param("now") Instant now);

    @Insert("""
            INSERT INTO kb_collaboration_event (event_id, protocol_version, kind, business_key, doc_id, version_id,
                review_id, round_no, actor_user_id, occurred_at, submitted_at, decided_at)
            VALUES (#{eventId}, 1, #{kind}, #{businessKey}, #{documentId}, #{versionId}, #{reviewId}, #{roundNo},
                #{actorUserId}, #{occurredAt}, #{submittedAt}, #{decidedAt})
            ON CONFLICT (business_key) DO NOTHING
            """)
    int append(CollaborationEvent event);
}
