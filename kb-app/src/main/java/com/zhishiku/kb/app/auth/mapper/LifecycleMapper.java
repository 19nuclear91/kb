package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignments;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleProof;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleReceipt;
import com.zhishiku.kb.common.security.RoleSummary;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LifecycleMapper extends LifecycleAssignments {
    @Select("""
            SELECT r.id AS role_id, r.code, r.name FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
             WHERE ur.user_id = #{userId} ORDER BY r.id
            """)
    List<RoleSummary> userRoles(@Param("userId") long userId);

    @Select("""
            SELECT COALESCE(max(a.id), 0) FROM sys_identity_audit a
             WHERE (a.target_type = 'user' AND a.target_id = #{userId}
                    AND a.operation_scope IN ('USER_ROLE_ASSIGN', 'USER_ROLE_REVOKE'))
                OR (a.target_type = 'role' AND a.target_id IN (SELECT role_id FROM sys_user_role WHERE user_id = #{userId})
                    AND a.operation_scope IN ('ROLE_RENAME', 'ROLE_PERMISSION_GRANT', 'ROLE_PERMISSION_REVOKE'))
            """)
    long roleFactRevision(@Param("userId") long userId);

    @Update("""
            UPDATE sys_identity_lifecycle_proof SET consumed_at = now()
             WHERE target_user_id = #{userId} AND operation = 'RESTORE' AND consumed_at IS NULL
            """)
    int invalidateRestorationProofs(@Param("userId") long userId);
    @Select("""
            SELECT EXISTS (SELECT 1 FROM sys_identity_lifecycle_proof
              WHERE target_user_id = #{userId} AND operation = 'RESTORE' AND consumed_at IS NULL AND expires_at > now())
            """)
    boolean restorationInFlight(@Param("userId") long userId);

    @Insert("""
            INSERT INTO sys_identity_lifecycle (user_id, kind, source, enterprise_id, employee_id, connection_id, registered_by)
            VALUES (#{userId}, 'MANAGED', 'dingtalk', #{enterpriseId}, #{employeeId}, #{connectionId}, #{actor})
            ON CONFLICT (user_id) DO UPDATE
               SET kind = 'MANAGED', source = 'dingtalk', enterprise_id = EXCLUDED.enterprise_id,
                   employee_id = EXCLUDED.employee_id, connection_id = EXCLUDED.connection_id, registered_by = EXCLUDED.registered_by,
                   version = sys_identity_lifecycle.version + 1, updated_at = now(),
                   last_event_id = CASE WHEN (sys_identity_lifecycle.source, sys_identity_lifecycle.enterprise_id, sys_identity_lifecycle.employee_id)
                       IS DISTINCT FROM ('dingtalk', EXCLUDED.enterprise_id, EXCLUDED.employee_id) THEN NULL ELSE sys_identity_lifecycle.last_event_id END,
                   restored_at = CASE WHEN (sys_identity_lifecycle.source, sys_identity_lifecycle.enterprise_id, sys_identity_lifecycle.employee_id)
                       IS DISTINCT FROM ('dingtalk', EXCLUDED.enterprise_id, EXCLUDED.employee_id) THEN NULL ELSE sys_identity_lifecycle.restored_at END
             WHERE sys_identity_lifecycle.kind IN ('NONE', 'MANAGED') AND sys_identity_lifecycle.restriction = 'CLEAR'
                   AND sys_identity_lifecycle.version = #{version}
            """)
    int registerEmployee(@Param("userId") long userId, @Param("version") long version, @Param("enterpriseId") String enterpriseId,
                         @Param("employeeId") String employeeId, @Param("connectionId") String connectionId, @Param("actor") long actor);

    @Update("""
            UPDATE sys_identity_lifecycle SET kind = 'NONE', source = NULL, enterprise_id = NULL, employee_id = NULL,
                   connection_id = NULL, version = version + 1, updated_at = now()
             WHERE user_id = #{userId} AND kind = 'MANAGED' AND restriction = 'CLEAR' AND version = #{version}
            """)
    int exitEmployee(@Param("userId") long userId, @Param("version") long version);

    @Insert("""
            INSERT INTO sys_identity_lifecycle_proof
                (token_hash, actor_user_id, target_user_id, operation, binding_digest, evidence, expires_at, created_at)
            VALUES (#{tokenHash}, #{actorUserId}, #{targetUserId}, #{operation}, #{bindingDigest},
                    CAST(#{evidence} AS jsonb), #{expiresAt}, #{createdAt})
            """)
    int insertProof(LifecycleProof proof);

    @Select("""
            SELECT token_hash, actor_user_id, target_user_id, operation, binding_digest, evidence::text AS evidence,
                   expires_at, consumed_at, created_at
              FROM sys_identity_lifecycle_proof WHERE token_hash = #{hash} FOR UPDATE
            """)
    LifecycleProof lockProof(@Param("hash") String hash);

    @Update("""
            UPDATE sys_identity_lifecycle_proof SET consumed_at = #{now}
             WHERE token_hash = #{hash} AND consumed_at IS NULL AND expires_at > #{now}
            """)
    int consumeProof(@Param("hash") String hash, @Param("now") Instant now);

    @Select("""
            SELECT actor_user_id, request_id, operation, target_key, binding_digest, response::text AS response
              FROM sys_identity_lifecycle_receipt WHERE actor_user_id = #{actor} AND request_id = #{requestId}
            """)
    LifecycleReceipt findReceipt(@Param("actor") long actor, @Param("requestId") String requestId);

    @Insert("""
            INSERT INTO sys_identity_lifecycle_receipt
                (actor_user_id, request_id, operation, target_key, binding_digest, response)
            VALUES (#{actorUserId}, #{requestId}, #{operation}, #{targetKey}, #{bindingDigest}, CAST(#{response} AS jsonb))
            """)
    int insertReceipt(LifecycleReceipt receipt);

    @Insert("""
            INSERT INTO sys_identity_lifecycle (user_id, kind, registered_by)
            VALUES (#{userId}, 'EMERGENCY', #{userId})
            ON CONFLICT (user_id) DO UPDATE SET kind = 'EMERGENCY', registered_by = EXCLUDED.registered_by,
                   version = sys_identity_lifecycle.version + 1, updated_at = now()
             WHERE sys_identity_lifecycle.kind IN ('NONE', 'EMERGENCY') AND sys_identity_lifecycle.restriction = 'CLEAR'
            """)
    int registerEmergency(@Param("userId") long userId);

    @Update("""
            UPDATE sys_identity_lifecycle SET kind = 'NONE', version = version + 1, updated_at = now()
             WHERE user_id = #{userId} AND kind = 'EMERGENCY' AND version = #{version}
            """)
    int retireEmergency(@Param("userId") long userId, @Param("version") long version);
    String COLUMNS = "user_id, kind, source, enterprise_id, employee_id, connection_id, restriction, "
            + "version, last_event_id, restored_at, registered_by, created_at, updated_at";

    @Override
    @Select("SELECT " + COLUMNS + " FROM sys_identity_lifecycle WHERE user_id = #{userId}")
    LifecycleAssignment findAssignment(@Param("userId") long userId);

    @Override
    @Select("SELECT " + COLUMNS + " FROM sys_identity_lifecycle WHERE source = #{source} "
            + "AND enterprise_id = #{enterpriseId} AND employee_id = #{employeeId} AND kind = 'MANAGED'")
    LifecycleAssignment findEmployee(@Param("source") String source, @Param("enterpriseId") String enterpriseId,
                                     @Param("employeeId") String employeeId);

    @Select("SELECT " + COLUMNS + " FROM sys_identity_lifecycle WHERE user_id = #{userId} FOR UPDATE")
    LifecycleAssignment lockAssignment(@Param("userId") long userId);

    @Update("""
            UPDATE sys_identity_lifecycle SET restriction = #{restriction}, last_event_id = #{eventId},
                   restored_at = #{restoredAt}, version = version + 1, updated_at = now()
             WHERE user_id = #{userId} AND version = #{version} AND kind = 'MANAGED'
            """)
    int updateRestriction(@Param("userId") long userId, @Param("version") long version,
                          @Param("restriction") LifecycleAssignment.Restriction restriction,
                          @Param("eventId") Long eventId, @Param("restoredAt") Instant restoredAt);

    @Insert("""
            INSERT INTO sys_identity_lifecycle_history
                (user_id, kind, source, enterprise_id, employee_id, connection_id, restriction, version,
                 last_event_id, restored_at, actor_user_id, operation, reason)
            SELECT user_id, kind, source, enterprise_id, employee_id, connection_id, restriction, version,
                   last_event_id, restored_at, #{actor}, #{operation}, #{reason}
              FROM sys_identity_lifecycle WHERE user_id = #{userId}
            """)
    int appendHistory(@Param("userId") long userId, @Param("actor") Long actor,
                      @Param("operation") String operation, @Param("reason") String reason);
}
