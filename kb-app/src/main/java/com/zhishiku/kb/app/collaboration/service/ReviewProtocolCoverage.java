package com.zhishiku.kb.app.collaboration.service;

import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 仅在旧生产者全部排空的部署契约下启用，不回填升级前覆盖。 */
@Component
public class ReviewProtocolCoverage {
    private final CollaborationEventMapper mapper;

    public ReviewProtocolCoverage(CollaborationEventMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void start() {
        int rows = mapper.startCoverage(Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (rows < 0 || rows > 1) throw new IllegalStateException("Review coverage was not saved");
    }
}
