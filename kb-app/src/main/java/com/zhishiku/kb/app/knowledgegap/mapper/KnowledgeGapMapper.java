package com.zhishiku.kb.app.knowledgegap.mapper;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapWorkflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/** 本域读写；每个调用由服务端当前身份驱动，无跨请求权限或二级缓存。 */
@Mapper
public interface KnowledgeGapMapper {
    KnowledgeGapRows.Gap findVisible(@Param("gapId") long gapId, @Param("actor") long actor);

    KnowledgeGapRows.Gap lock(@Param("gapId") long gapId, @Param("actor") long actor);

    List<KnowledgeGapRows.Gap> list(@Param("actor") long actor, @Param("view") String view,
                                   @Param("status") KnowledgeGapState status, @Param("beforeId") Long beforeId,
                                   @Param("limit") int limit);

    List<KnowledgeGapRows.Event> history(@Param("gapId") long gapId, @Param("actor") long actor,
                                        @Param("afterVersion") Long afterVersion, @Param("limit") int limit);

    KnowledgeGapRows.Entry entry(@Param("gapId") long gapId, @Param("entryId") UUID entryId,
                                 @Param("actor") long actor);

    String configureLockTimeout();

    int claim(@Param("binding") KnowledgeGapRows.Binding binding);

    KnowledgeGapRows.Command command(@Param("requestId") String requestId);

    int complete(@Param("requestId") String requestId, @Param("receipt") KnowledgeGapRows.Receipt receipt);

    int ensureActorGuard(@Param("actor") long actor);

    Long lockActorGuard(@Param("actor") long actor);

    long countOpen(@Param("actor") long actor);

    KnowledgeGapRows.Gap create(@Param("actor") long actor, @Param("input") KnowledgeGapInputs.Creation input,
                                @Param("consentVersion") String consentVersion, @Param("eventId") UUID eventId);

    int advance(@Param("gapId") long gapId, @Param("actor") long actor, @Param("expectedVersion") long expectedVersion,
                @Param("workflow") KnowledgeGapWorkflow workflow);

    int insertEntry(@Param("entry") KnowledgeGapRows.Entry entry);

    int insertEvent(@Param("event") KnowledgeGapRows.Event event);

    int insertReceptionRecipients(@Param("eventId") UUID eventId);

    int insertRecipients(@Param("eventId") UUID eventId, @Param("userIds") List<Long> userIds);
}
