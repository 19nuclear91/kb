package com.zhishiku.kb.app.telemetry;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** 只读取绑定、校验后的本地开关，不附加主体或资源标签。 */
@Component
public class MonitoringFeatureMetrics implements MeterBinder {
    private final ConversationProperties conversation;
    private final AiObservationProperties observation;

    public MonitoringFeatureMetrics(ConversationProperties conversation, AiObservationProperties observation) {
        this.conversation = conversation;
        this.observation = observation;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("kb.monitoring.feature.enabled", conversation, value -> value.isEnabled() ? 1 : 0)
                .tag("feature", "conversation").register(registry);
        Gauge.builder("kb.monitoring.feature.enabled", observation, value -> value.isEnabled() ? 1 : 0)
                .tag("feature", "ai_observation").register(registry);
    }
}
