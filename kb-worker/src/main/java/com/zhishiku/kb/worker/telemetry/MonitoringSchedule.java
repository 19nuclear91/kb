package com.zhishiku.kb.worker.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MonitoringSchedule {
    private final long delayMillis;

    public MonitoringSchedule(@Value("${kb.worker.metrics-gauge-interval-ms:30000}") long delayMillis) {
        if (delayMillis < 1000 || delayMillis > 3600000) {
            throw new IllegalArgumentException("Invalid monitoring sample interval");
        }
        this.delayMillis = delayMillis;
    }

    public long getDelayMillis() {
        return delayMillis;
    }
}
