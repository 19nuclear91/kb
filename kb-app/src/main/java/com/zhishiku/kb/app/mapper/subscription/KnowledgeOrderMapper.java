package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.common.review.PublicationOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.UUID;

/** 原业务事务内取得数据库顺序并固定发布上下文，不执行订阅扇出。 */
@Mapper
public interface KnowledgeOrderMapper {
    @Select(value = "SELECT position, business_time FROM kb_knowledge_next_order()", affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE, timeout = 10)
    PublicationOrder next();

    @Select(value = "SELECT kb_knowledge_capture_publication(#{eventId}, #{position}, #{at})", affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE, timeout = 10)
    int capture(
            @Param("eventId")
            UUID eventId,
            @Param("position")
            long position,
            @Param("at")
            Instant at
    );

    @Select("SELECT coverage_at FROM kb_knowledge_order WHERE singleton AND protocol_version = 1")
    Instant coverageAt();
}
