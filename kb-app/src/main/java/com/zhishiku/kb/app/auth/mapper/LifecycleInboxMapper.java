package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.lifecycle.LifecycleEventRecord;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleInbox;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleItem;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleItemStatus;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleEventBoundary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LifecycleInboxMapper extends LifecycleInbox {
    @Select("""
            SELECT COALESCE(max(e.id), 0) AS last_event_id, count(*) AS event_count,
                   count(*) FILTER (WHERE e.conflicted) AS conflicts,
                   count(*) FILTER (WHERE i.status IN ('UNMAPPED', 'FAILED') AND i.resolved_at IS NULL) AS pending,
                   count(*) FILTER (WHERE i.status = 'REVIEW' AND i.resolved_at IS NULL) AS review
              FROM sys_identity_lifecycle_event e JOIN sys_identity_lifecycle_item i ON i.event_id = e.id
             WHERE e.enterprise_id = #{enterpriseId} AND i.employee_id = #{employeeId} AND e.event_type = 'user_leave_org'
            """)
    LifecycleEventBoundary boundary(@Param("enterpriseId") String enterpriseId, @Param("employeeId") String employeeId);

    @Update("""
            UPDATE sys_identity_lifecycle_item SET resolved_at = #{now}, updated_at = now()
             WHERE user_id = #{userId} AND event_id <= #{boundary} AND status = 'REVIEW' AND resolved_at IS NULL
            """)
    int resolveReview(@Param("userId") long userId, @Param("boundary") long boundary, @Param("now") Instant now);
    String EVENT_COLUMNS = "id, source_scope, event_id, event_type, enterprise_id, connection_id, born_at, content_digest, conflicted, received_at";
    String ITEM_COLUMNS = "id, event_id, employee_id, user_id, mapping_version, applied_auth_epoch, status, error_code, attempts, next_attempt_at, created_at, updated_at, resolved_at";

    @Select("SELECT nextval('sys_identity_lifecycle_event_id_seq')")
    long nextId();

    @Override
    @Select("SELECT " + EVENT_COLUMNS + " FROM sys_identity_lifecycle_event WHERE source_scope = #{scope} AND event_id = #{eventId}")
    LifecycleEventRecord findEvent(@Param("scope") String scope, @Param("eventId") String eventId);

    @Select("SELECT " + EVENT_COLUMNS + " FROM sys_identity_lifecycle_event WHERE id = #{id}")
    LifecycleEventRecord findEventById(@Param("id") long id);

    @Override
    @Insert("""
            INSERT INTO sys_identity_lifecycle_event
                (id, source_scope, event_id, event_type, enterprise_id, connection_id, born_at, content_digest, conflicted, received_at)
            VALUES (#{id}, #{sourceScope}, #{eventId}, #{eventType}, #{enterpriseId}, #{connectionId}, #{bornAt},
                    #{contentDigest}, #{conflicted}, #{receivedAt})
            """)
    int insertEvent(LifecycleEventRecord event);

    @Override
    @Update("UPDATE sys_identity_lifecycle_event SET conflicted = true WHERE id = #{id}")
    int markEventConflict(@Param("id") long id);

    @Update("""
            UPDATE sys_identity_lifecycle_item SET status = 'REVIEW', error_code = 'EVENT_CONFLICT', next_attempt_at = NULL,
                   updated_at = now()
             WHERE event_id = #{id} AND status IN ('UNMAPPED', 'FAILED')
            """)
    int isolateUnresolvedConflict(@Param("id") long id);

    @Insert("""
            INSERT INTO sys_identity_lifecycle_item (event_id, employee_id, status)
            VALUES (#{eventId}, #{employeeId}, 'UNMAPPED')
            """)
    int insertEmployee(@Param("eventId") long eventId, @Param("employeeId") String employeeId);

    @Select("SELECT " + ITEM_COLUMNS + " FROM sys_identity_lifecycle_item WHERE event_id = #{eventId} ORDER BY id LIMIT 1001")
    List<LifecycleItem> findItems(@Param("eventId") long eventId);

    @Update("""
            UPDATE sys_identity_lifecycle_item
               SET user_id = #{userId}, mapping_version = #{mappingVersion}, applied_auth_epoch = #{epoch},
                   status = #{status}, error_code = #{error}, attempts = attempts + 1, next_attempt_at = NULL, updated_at = now()
             WHERE id = #{id} AND status IN ('UNMAPPED', 'REVIEW', 'FAILED')
            """)
    int updateItem(@Param("id") long id, @Param("userId") Long userId, @Param("mappingVersion") Long mappingVersion,
                   @Param("epoch") Long epoch, @Param("status") LifecycleItemStatus status, @Param("error") String error);

    @Select("""
            SELECT DISTINCT event_id FROM sys_identity_lifecycle_item
             WHERE status = 'FAILED' AND next_attempt_at <= #{now} ORDER BY event_id LIMIT 4
            """)
    List<Long> dueEvents(@Param("now") Instant now);

    @Update("""
            UPDATE sys_identity_lifecycle_item SET status = 'FAILED', error_code = 'DEPENDENCY_UNAVAILABLE',
                   attempts = attempts + 1, next_attempt_at = now() + interval '1 second' * LEAST(300, 5 * power(2, LEAST(attempts, 6))),
                   updated_at = now()
             WHERE event_id = #{eventId} AND status IN ('UNMAPPED', 'FAILED')
            """)
    int recordRetryFailure(@Param("eventId") long eventId);

    @Select("""
            SELECT e.id FROM sys_identity_lifecycle_event e JOIN sys_identity_lifecycle_item i ON i.event_id = e.id
             WHERE e.enterprise_id = #{enterpriseId} AND i.employee_id = #{employeeId}
               AND e.event_type = 'user_leave_org' AND i.status IN ('UNMAPPED', 'REVIEW', 'FAILED') AND i.resolved_at IS NULL
             ORDER BY e.id LIMIT 51
            """)
    List<Long> unresolvedEmployeeEvents(@Param("enterpriseId") String enterpriseId, @Param("employeeId") String employeeId);
}
