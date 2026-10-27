package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.config.UuidTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 不关联可被删除的目标实体，不读取自由文本 reason。
 */
@Mapper
public interface AuditQueryMapper {
    @Select("""
            <script>
            SELECT id, event_id, operation_scope, request_id, actor_user_id, target_type,
                   target_id, operation, status, before_state::text AS before_state,
                   after_state::text AS after_state, created_at
              FROM sys_identity_audit
             WHERE created_at &gt;= #{f.from} AND created_at &lt; #{f.to}
              <if test="f.actorUserId != null">AND actor_user_id = #{f.actorUserId}</if>
              <if test="f.operationScope != null">AND operation_scope = #{f.operationScope}</if>
              <if test="f.operation != null">AND operation = #{f.operation}</if>
              <if test="f.targetType != null">AND target_type = #{f.targetType}</if>
              <if test="f.targetId != null">AND target_id = #{f.targetId}</if>
              <if test="f.status != null">AND status = #{f.status}</if>
              <if test="f.requestId != null">AND request_id = #{f.requestId}</if>
              <if test="f.loginUsername != null">
                AND operation_scope IN ('AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILURE')
                AND kb_audit_login_username(after_state) = #{f.loginUsername}
              </if>
              <if test="f.beforeTime != null">
                AND (created_at, id) &lt; (#{f.beforeTime}, #{f.beforeId})
              </if>
             ORDER BY created_at DESC, id DESC LIMIT #{f.fetch}
            </script>
            """)
    @Results(id = "queryAudit", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "event_id", property = "eventId", typeHandler = UuidTypeHandler.class),
            @Result(column = "operation_scope", property = "operationScope"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "actor_user_id", property = "actorUserId"),
            @Result(column = "target_type", property = "targetType"),
            @Result(column = "target_id", property = "targetId"),
            @Result(column = "operation", property = "operation"),
            @Result(column = "status", property = "status"),
            @Result(column = "before_state", property = "beforeState"),
            @Result(column = "after_state", property = "afterState"),
            @Result(column = "created_at", property = "createdAt")})
    List<IdentityAuditRecord> list(@Param("f") AuditQueryFilter filter);

    @Select("""
            SELECT id, event_id, operation_scope, request_id, actor_user_id, target_type,
                   target_id, operation, status, before_state::text AS before_state,
                   after_state::text AS after_state, created_at
              FROM sys_identity_audit WHERE event_id = #{eventId, typeHandler=com.zhishiku.kb.app.config.UuidTypeHandler}
            """)
    @ResultMap("queryAudit")
    IdentityAuditRecord detail(@Param("eventId") UUID eventId);
}
