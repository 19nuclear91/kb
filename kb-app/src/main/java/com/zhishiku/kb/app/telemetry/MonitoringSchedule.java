package com.zhishiku.kb.app.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 独立启用调度；不依赖会话或 AI 观测组件。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MonitoringSchedule {
    private final long delayMillis;

    public MonitoringSchedule(@Value("${kb.monitoring.sample-interval-ms:30000}") long delayMillis) {
        if (delayMillis < 1000 || delayMillis > 3600000) {
            throw new IllegalArgumentException("Invalid monitoring sample interval");
        }
        this.delayMillis = delayMillis;
    }

    public long getDelayMillis() {
        return delayMillis;
    }
}
