package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.domain.subscription.KnowledgeDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 订阅匹配、周期发现与提醒工作持久化；每次写入由独立短事务封装。 */
@Mapper
public interface KnowledgeDerivationMapper {
    int discover(int limit);

    boolean sourceReady(UUID eventId);

    int waitSource(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner,
            @Param("delay")
            int delay
    );

    List<KnowledgeWork.Claim> claimMatches(
            @Param("owner")
            String owner,
            @Param("limit")
            int limit,
            @Param("leaseSeconds")
            int leaseSeconds
    );

    Long lockMatch(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner
    );

    List<Long> recipients(
            @Param("eventId")
            UUID eventId,
            @Param("after")
            long after,
            @Param("limit")
            int limit
    );

    List<KnowledgeWork.Match> matches(
            @Param("eventId")
            UUID eventId,
            @Param("users")
            List<Long> users
    );

    UUID saveUpdate(KnowledgeWork.Update update);

    int saveMatch(
            @Param("updateId")
            UUID updateId,
            @Param("userId")
            long userId,
            @Param("generationId")
            UUID generationId
    );

    int registerImmediate(
            @Param("updateId")
            UUID updateId,
            @Param("userId")
            long userId
    );

    int advanceMatch(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner,
            @Param("cursor")
            long cursor,
            @Param("done")
            boolean done,
            @Param("leaseSeconds")
            int leaseSeconds
    );

    LocalDate nextWeek();

    int createWeek(
            @Param("week")
            LocalDate week,
            @Param("position")
            long position,
            @Param("at")
            Instant at
    );

    List<KnowledgeWork.Claim> claimWeeks(
            @Param("owner")
            String owner,
            @Param("limit")
            int limit,
            @Param("leaseSeconds")
            int leaseSeconds
    );

    Long lockWeek(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner
    );

    boolean weekComplete(LocalDate week);

    List<Long> weeklyRecipients(
            @Param("week")
            LocalDate week,
            @Param("after")
            long after,
            @Param("limit")
            int limit
    );

    int registerWeekly(
            @Param("week")
            LocalDate week,
            @Param("userId")
            long userId
    );

    int advanceWeek(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner,
            @Param("cursor")
            long cursor,
            @Param("done")
            boolean done,
            @Param("leaseSeconds")
            int leaseSeconds
    );

    List<KnowledgeWork.Claim> claimDeliveries(
            @Param("owner")
            String owner,
            @Param("limit")
            int limit,
            @Param("leaseSeconds")
            int leaseSeconds
    );

    KnowledgeDelivery lockDelivery(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner
    );

    int finishDelivery(
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner,
            @Param("notified")
            boolean notified,
            @Param("position")
            long position,
            @Param("at")
            Instant at
    );

    int notify(KnowledgeDelivery delivery);

    int release(
            @Param("stage")
            String stage,
            @Param("claim")
            KnowledgeWork.Claim claim,
            @Param("owner")
            String owner,
            @Param("delay")
            int delay,
            @Param("failed")
            boolean failed
    );

    KnowledgeWork.Backlog backlog();
}
