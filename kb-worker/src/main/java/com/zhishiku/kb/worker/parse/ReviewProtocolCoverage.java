package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 与 app 使用同一追加 coverage；不从旧任务或索引时间推断覆盖。 */
@Component
public class ReviewProtocolCoverage {
    private final ReviewFactMapper mapper;

    public ReviewProtocolCoverage(ReviewFactMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void start() {
        int rows = mapper.startCoverage(Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (rows < 0 || rows > 1) throw new IllegalStateException("Review coverage was not saved");
    }
}
