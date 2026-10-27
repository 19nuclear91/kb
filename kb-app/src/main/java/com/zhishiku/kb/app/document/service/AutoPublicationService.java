package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutoPublicationService {
    private static final Logger LOG = LoggerFactory.getLogger(AutoPublicationService.class);
    private final DocumentPublicationMapper mapper;
    private final AutoPublicationTransaction transaction;

    public AutoPublicationService(DocumentPublicationMapper mapper, AutoPublicationTransaction transaction) {
        this.mapper = mapper;
        this.transaction = transaction;
    }

    public long scan(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid publication batch");
        var candidates = mapper.autoCandidates(afterId, limit);
        if (candidates == null || candidates.size() > limit) throw new IllegalStateException("Publication candidates unavailable");
        for (var source : candidates) {
            try {
                transaction.publish(source);
            } catch (RuntimeException unavailable) {
                // 不记录底层 SQL 或文档内容；失败候选将在循环扫描时再次复核。
                LOG.warn("AUTO 发布巡检未确认成功 code=SERVICE_UNAVAILABLE");
            }
        }
        return candidates.size() < limit ? 0 : candidates.getLast().versionId();
    }
}
