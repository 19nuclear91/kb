package com.zhishiku.kb.app.domain.dto.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 本人更新和周报同源可见性查询，dispatchPosition仅供发送或原历史成员判定。 */
public record KnowledgeQuery(
        long actor,
        List<Long> spaceIds,
        List<String> principalKeys,
        short clearance,
        UUID updateId,
        UUID digestId,
        Long documentId,
        LocalDate weekStart,
        Long dispatchPosition,
        Long beforePosition,
        Long beforeDocumentId,
        LocalDate beforeWeek,
        int limit
) {
}
