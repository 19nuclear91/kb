package com.zhishiku.kb.app.conversation.mapper;

import com.zhishiku.kb.app.conversation.model.BatchRow;
import com.zhishiku.kb.app.conversation.model.CitationRow;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.SourceRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 写路径始终先锁会话再锁轮次，由短事务服务编排。
 */
@Mapper
public interface ConversationMapper {
    ConversationRow find(@Param("id") long id);

    ConversationRow lock(@Param("id") long id);

    ConversationRow findCreation(@Param("owner") long owner, @Param("request") String request);

    int insertConversation(ConversationRow row);

    int updateConversation(ConversationRow row);

    List<ConversationRow> list(@Param("owner") long owner, @Param("before") Instant before,
                               @Param("beforeId") Long beforeId, @Param("limit") int limit);

    TurnRow turn(@Param("id") UUID id);

    TurnRow lockTurn(@Param("id") UUID id);

    TurnRow request(@Param("conversation") long conversation, @Param("request") String request);

    List<TurnRow> turns(@Param("conversation") long conversation, @Param("after") long after,
                        @Param("limit") int limit);

    List<TurnRow> history(@Param("conversation") long conversation, @Param("limit") int limit);

    int insertTurn(TurnRow row);

    int updateTurn(TurnRow row);

    List<SourceRow> sources(@Param("turn") UUID turn);

    List<CitationRow> citations(@Param("turn") UUID turn);

    int insertSource(SourceRow row);

    int insertCitation(CitationRow row);

    BatchRow batch(@Param("turn") UUID turn, @Param("sequence") long sequence);

    int insertBatch(BatchRow row);

    SettlementRow settlement(@Param("turn") UUID turn);

    int insertSettlement(SettlementRow row);

    int updateSettlement(SettlementRow row);

    int deliveryResult(@Param("turn") UUID turn, @Param("attempt") int attempt, @Param("claim") Instant claim,
                       @Param("state") String state, @Param("next") Instant next, @Param("settled") Instant settled);

    int ackSettlement(@Param("turn") UUID turn, @Param("digest") String digest, @Param("now") Instant now);

    List<Long> expired(@Param("now") Instant now, @Param("limit") int limit);

    List<SettlementRow> claimSettlements(@Param("now") Instant now, @Param("until") Instant until,
                                         @Param("limit") int limit);

    int clearCitations(@Param("conversation") long conversation);

    int clearSources(@Param("conversation") long conversation);

    int scrubTurns(@Param("conversation") long conversation);
}

