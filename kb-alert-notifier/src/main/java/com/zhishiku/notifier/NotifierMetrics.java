package com.zhishiku.notifier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class NotifierMetrics {
    enum RequestOutcome { success, rejected, failure }
    enum DeliveryOutcome { success, remote_http, remote_business, remote_json, timeout, network, cancelled, internal }

    private final Map<RequestOutcome, Counter> requests = new EnumMap<>(RequestOutcome.class);
    private final Map<DeliveryOutcome, Counter> deliveries = new EnumMap<>(DeliveryOutcome.class);
    private final Map<DeliveryOutcome, Timer> durations = new EnumMap<>(DeliveryOutcome.class);
    private final Map<Rejection.Reason, Counter> rejections = new EnumMap<>(Rejection.Reason.class);

    NotifierMetrics(MeterRegistry registry, Admission admission) {
        for (RequestOutcome outcome : RequestOutcome.values()) {
            requests.put(outcome, registry.counter("kb.alert.notifier.requests", "outcome", outcome.name()));
        }
        for (DeliveryOutcome outcome : DeliveryOutcome.values()) {
            deliveries.put(outcome, registry.counter("kb.alert.notifier.deliveries", "outcome", outcome.name()));
            durations.put(outcome, registry.timer("kb.alert.notifier.delivery.duration", "outcome", outcome.name()));
        }
        for (Rejection.Reason reason : Rejection.Reason.values()) {
            rejections.put(reason, registry.counter("kb.alert.notifier.rejections", "reason", reason.name()));
        }
        Gauge.builder("kb.alert.notifier.inflight", admission, Admission::inflight).register(registry);
    }

    void request(int status) {
        requests.get(status == 204 ? RequestOutcome.success : status < 500 ? RequestOutcome.rejected : RequestOutcome.failure).increment();
    }

    void reject(Rejection.Reason reason) {
        rejections.get(reason).increment();
    }

    void delivery(DeliveryOutcome outcome, long duration) {
        deliveries.get(outcome).increment();
        durations.get(outcome).record(Math.max(0, duration), TimeUnit.NANOSECONDS);
    }
}
