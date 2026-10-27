package com.zhishiku.kb.app.aiusage.mapper;

import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 元数据存储与有界查询；发布锁必须在事件 ID 分配前取得。
 */
public interface AiUsageMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{id}, 628114))")
    int registrationLock(String id);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(62811401)")
    int publicationLock();

    @Select("SELECT pg_try_advisory_xact_lock(62811402)")
    boolean cleanupLock();

    AiCallRow call(@Param("id") String id);
    AiCallRow lockCall(@Param("id") String id);
    int insertCall(AiCallRow row);
    int updateCall(AiCallRow row);
    AiEventRow event(@Param("id") String id);
    AiEventRow phase(@Param("call") String call, @Param("kind") String kind);
    int insertEvent(AiEventRow row);
    List<AiCallRow> expired(@Param("now") Instant now, @Param("limit") int limit);
    List<AiCallRow> unconfirmed(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
    List<AiCallRow> cleanupCandidates(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
    int deleteEvents(@Param("id") String id);
    int deleteCall(@Param("id") String id);
    Instant deletionBoundary(@Param("id") String id);
    int advanceCoverage(@Param("through") Instant through);
    Instant clearedThrough();
    long upperEventId();
    List<AiEventRow> events(Map<String, Object> parameters);
    Map<String, Object> aggregate(Map<String, Object> parameters);
    List<Map<String, Object>> groups(Map<String, Object> parameters);
    Instant firstCoverage();
    List<Map<String, Object>> periods(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
    int openPeriod(@Param("now") Instant now, @Param("days") int days);
    int closePeriod(@Param("now") Instant now);
    int closeChangedPolicy(@Param("now") Instant now, @Param("days") int days);
    int pruneInstances(@Param("now") Instant now);
    int heartbeat(@Param("id") String id, @Param("enabled") boolean enabled, @Param("days") int days, @Param("until") Instant until);
    int inconsistentInstances(@Param("now") Instant now, @Param("enabled") boolean enabled, @Param("days") int days);
    Map<String, Object> operationalMetrics();
}
