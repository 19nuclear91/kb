package com.zhishiku.kb.app.document.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只保留扫描位置，不缓存主体资格或发布判定。 */
@Component
public class AutoPublicationScheduler {
    private final AutoPublicationService service;
    private long cursor;

    public AutoPublicationScheduler(AutoPublicationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${kb.document-review.auto-publication-delay-ms:30000}")
    public void tick() {
        cursor = service.scan(cursor, 50);
    }
}
