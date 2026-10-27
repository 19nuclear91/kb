package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 不可变身份与部门审计事件的数据库边界。
 */
@Mapper
public interface IdentityAuditMapper {

    @Select("""
            SELECT id, event_id, operation_scope, request_id, actor_user_id,
                   target_type, target_id, operation, status, before_state::text,
                   after_state::text, reason, created_at
              FROM sys_identity_audit
             WHERE operation_scope = #{scope} AND request_id = #{requestId}
            """)
    IdentityAuditRecord findByRequest(@Param("scope") String scope,
                                      @Param("requestId") String requestId);

    @Insert("""
            INSERT INTO sys_identity_audit
                (event_id, operation_scope, request_id, actor_user_id, target_type,
                 target_id, operation, status, before_state, after_state, reason, created_at)
            VALUES (#{eventId}, #{scope}, #{requestId}, #{actorUserId}, #{targetType},
                    #{targetId}, #{operation}, #{status}, CAST(#{beforeState} AS jsonb),
                    CAST(#{afterState} AS jsonb), #{reason}, #{createdAt})
            """)
    int insert(@Param("eventId") UUID eventId,
               @Param("scope") String scope,
               @Param("requestId") String requestId,
               @Param("actorUserId") Long actorUserId,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId,
               @Param("operation") String operation,
               @Param("status") String status,
               @Param("beforeState") String beforeState,
               @Param("afterState") String afterState,
               @Param("reason") String reason,
               @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT id, event_id, operation_scope, request_id, actor_user_id,
                   target_type, target_id, operation, status, before_state::text,
                   after_state::text, reason, created_at
              FROM sys_identity_audit
             WHERE target_type = #{targetType} AND target_id = #{targetId}
               AND created_at >= #{from} AND created_at < #{to}
             ORDER BY created_at DESC, id DESC
            """)
    List<IdentityAuditRecord> list(@Param("targetType") String targetType,
                                   @Param("targetId") Long targetId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);
}
