package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.lifecycle.SessionCleanupJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface SessionCleanupMapper {
    @Select("""
            SELECT user_id, revoked_through_epoch, attempts FROM sys_identity_session_cleanup
             WHERE status = 'PENDING' AND next_attempt_at <= #{now}
               AND (lease_until IS NULL OR lease_until <= #{now})
             ORDER BY next_attempt_at, user_id, revoked_through_epoch LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    SessionCleanupJob next(@Param("now") Instant now);

    @Update("""
            UPDATE sys_identity_session_cleanup SET lease_owner = #{owner}, lease_until = #{until}
             WHERE user_id = #{userId} AND revoked_through_epoch = #{epoch} AND status = 'PENDING'
            """)
    int claim(@Param("userId") long userId, @Param("epoch") long epoch,
              @Param("owner") UUID owner, @Param("until") Instant until);

    @Update("""
            UPDATE sys_identity_session_cleanup SET status = CASE WHEN #{done} THEN 'DONE' ELSE 'PENDING' END,
                   completed_at = CASE WHEN #{done} THEN #{now} ELSE NULL END,
                   attempts = attempts + CASE WHEN #{failed} THEN 1 ELSE 0 END,
                   next_attempt_at = #{retryAt}, lease_owner = NULL, lease_until = NULL
             WHERE user_id = #{userId} AND revoked_through_epoch = #{epoch} AND lease_owner = #{owner}
            """)
    int release(@Param("userId") long userId, @Param("epoch") long epoch, @Param("owner") UUID owner,
                @Param("done") boolean done, @Param("failed") boolean failed, @Param("now") Instant now,
                @Param("retryAt") Instant retryAt);
}
