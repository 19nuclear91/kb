package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.common.security.OutputStopReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface OutputStopEvidenceMapper {
    @Insert("""
            INSERT INTO sys_identity_revocation_evidence (user_id, revoked_epoch, event_id, initial_reception)
            VALUES (#{userId}, #{epoch}, #{eventId}, #{initial})
            """)
    int revoke(@Param("userId") long userId, @Param("epoch") long epoch, @Param("eventId") long eventId,
               @Param("initial") boolean initial);

    @Update("""
            UPDATE sys_identity_lifecycle_event SET commit_after = clock_timestamp()
             WHERE id = #{eventId} AND commit_before IS NOT NULL AND commit_after IS NULL
            """)
    int receptionCommitted(@Param("eventId") long eventId);

    @Update("""
            UPDATE sys_identity_revocation_evidence SET barrier_after = clock_timestamp()
             WHERE event_id = #{eventId} AND barrier_after IS NULL
            """)
    int barriersCommitted(@Param("eventId") long eventId);

    @Insert("""
            INSERT INTO sys_identity_output_stop
                (execution_id, user_id, auth_epoch, checked_at, after_check_min_nanos, after_check_max_nanos, reason, binding_digest)
            VALUES (#{report.executionId}, #{report.userId}, #{report.authEpoch}, #{report.checkedAt},
                #{report.afterCheckMinNanos}, #{report.afterCheckMaxNanos}, #{report.reason}, #{digest})
            ON CONFLICT (execution_id) DO NOTHING
            """)
    int stopped(@Param("report") OutputStopReport report, @Param("digest") String digest);

    @Select("SELECT binding_digest FROM sys_identity_output_stop WHERE execution_id = #{id}")
    String binding(@Param("id") UUID id);
}
