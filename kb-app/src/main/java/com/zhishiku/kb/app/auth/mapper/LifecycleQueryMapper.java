package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.lifecycle.LifecycleViews;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleQueryMapper {
    @Select("""
            <script>
            SELECT e.id, e.event_type, e.conflicted, e.born_at, e.received_at AS recorded_at,
                   (SELECT count(*) FROM sys_identity_lifecycle_item i WHERE i.event_id = e.id AND i.status = 'OFFBOARDED') AS restricted,
                   (SELECT count(*) FROM sys_identity_lifecycle_item i WHERE i.event_id = e.id AND i.status = 'REVIEW' AND i.resolved_at IS NULL) AS review,
                   (SELECT count(*) FROM sys_identity_lifecycle_item i WHERE i.event_id = e.id AND i.status = 'UNMAPPED') AS unmapped,
                   (SELECT count(*) FROM sys_identity_lifecycle_item i WHERE i.event_id = e.id AND i.status = 'FAILED') AS failed
              FROM sys_identity_lifecycle_event e
             WHERE 1 = 1
               <if test="before != null">AND e.id &lt; #{before}</if>
               <if test="status != null">
                 <choose>
                   <when test="status == 'CONFLICT'">AND e.conflicted = true</when>
                   <when test="status == 'IGNORED'">AND e.event_type != 'user_leave_org' AND e.conflicted = false</when>
                   <otherwise>AND EXISTS (SELECT 1 FROM sys_identity_lifecycle_item i
                     WHERE i.event_id = e.id AND i.status = #{status} AND i.resolved_at IS NULL)</otherwise>
                 </choose>
               </if>
             ORDER BY e.id DESC LIMIT #{limit}
            </script>
            """)
    List<LifecycleViews.Event> events(@Param("status") String status, @Param("before") Long before, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, user_id, mapping_version, status,
                   CASE WHEN error_code IN ('UNMATCHED', 'LATE_EVENT', 'EVENT_CONFLICT', 'EMERGENCY_UNAVAILABLE', 'DEPENDENCY_UNAVAILABLE')
                        THEN error_code WHEN error_code IS NULL THEN NULL ELSE 'DEPENDENCY_UNAVAILABLE' END AS error_code,
                   attempts, updated_at, resolved_at
              FROM sys_identity_lifecycle_item
             WHERE event_id = #{eventId}
               <if test="after != null">AND id &gt; #{after}</if>
             ORDER BY id LIMIT #{limit}
            </script>
            """)
    List<LifecycleViews.Item> items(@Param("eventId") long eventId, @Param("after") Long after, @Param("limit") int limit);

    @Select("""
            SELECT (SELECT count(*) FROM sys_identity_lifecycle WHERE kind = 'MANAGED') AS managed,
                   (SELECT count(*) FROM sys_identity_lifecycle WHERE restriction != 'CLEAR') AS restricted,
                   count(*) FILTER (WHERE status = 'UNMAPPED') AS unmapped,
                   count(*) FILTER (WHERE status = 'REVIEW' AND resolved_at IS NULL) AS review,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed
              FROM sys_identity_lifecycle_item
            """)
    LifecycleViews.Counts counts();

    @Select("""
            <script>
            SELECT user_id FROM sys_identity_lifecycle WHERE kind = 'EMERGENCY'
              <if test="after != null">AND user_id &gt; #{after}</if>
             ORDER BY user_id LIMIT #{limit}
            </script>
            """)
    List<Long> emergencyUsers(@Param("after") Long after, @Param("limit") int limit);
}
