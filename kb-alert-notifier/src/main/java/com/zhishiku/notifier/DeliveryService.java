package com.zhishiku.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DeliveryService {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryService.class);
    private final NotifierSettings settings;
    private final Transport transport;
    private final Admission admission;
    private final NotifierMetrics metrics;
    private final Clock clock;
    private final LongSupplier ticker;

    DeliveryService(NotifierSettings settings, Transport transport, Admission admission,
            NotifierMetrics metrics, Clock clock, LongSupplier ticker) {
        this.settings = settings;
        this.transport = transport;
        this.admission = admission;
        this.metrics = metrics;
        this.clock = clock;
        this.ticker = ticker;
    }

    void deliver(byte[] input) {
        Webhook webhook = Webhook.decode(input, settings);
        byte[] body = Markdown.render(webhook, settings).payload();
        Deadline deadline = new Deadline(ticker);
        URI uri = DingTalkProtocol.target(clock.millis(), settings);
        deadline.remainingNanos();
        admission.enter();
        long started = ticker.getAsLong();
        NotifierMetrics.DeliveryOutcome outcome = NotifierMetrics.DeliveryOutcome.internal;
        try {
            Transport.Response response = transport.exchange(uri, body, deadline);
            deadline.remainingNanos();
            confirm(response);
            deadline.remainingNanos();
            outcome = NotifierMetrics.DeliveryOutcome.success;
        } catch (Rejection rejection) {
            outcome = deliveryOutcome(rejection.reason());
            throw new Rejection(503, rejection.reason());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = NotifierMetrics.DeliveryOutcome.cancelled;
            throw new Rejection(503, Rejection.Reason.cancelled);
        } catch (HttpTimeoutException exception) {
            outcome = NotifierMetrics.DeliveryOutcome.timeout;
            throw new Rejection(503, Rejection.Reason.timeout);
        } catch (Exception exception) {
            outcome = NotifierMetrics.DeliveryOutcome.network;
            throw new Rejection(503, Rejection.Reason.network);
        } finally {
            // 传输已同步退出；绝不在 future.cancel 时提前回收准入。
            admission.exit();
            metrics.delivery(outcome, ticker.getAsLong() - started);
            if (outcome != NotifierMetrics.DeliveryOutcome.success) {
                LOG.warn("notification_delivery outcome={}", outcome.name());
            }
        }
    }

    private static NotifierMetrics.DeliveryOutcome deliveryOutcome(Rejection.Reason reason) {
        return switch (reason) {
            case remote_http -> {
                yield NotifierMetrics.DeliveryOutcome.remote_http;
            }
            case remote_business -> {
                yield NotifierMetrics.DeliveryOutcome.remote_business;
            }
            case remote_json -> {
                yield NotifierMetrics.DeliveryOutcome.remote_json;
            }
            case timeout -> {
                yield NotifierMetrics.DeliveryOutcome.timeout;
            }
            case cancelled -> {
                yield NotifierMetrics.DeliveryOutcome.cancelled;
            }
            case network -> {
                yield NotifierMetrics.DeliveryOutcome.network;
            }
            default -> {
                yield NotifierMetrics.DeliveryOutcome.internal;
            }
        };
    }

    private static void confirm(Transport.Response response) {
        if (response.status() < 200 || response.status() >= 300) {
            throw new Rejection(503, Rejection.Reason.remote_http);
        }
        JsonNode json;
        try {
            json = BoundedJson.parse(response.body(), BoundedJson.RESPONSE_BYTES);
        } catch (Exception exception) {
            throw new Rejection(503, Rejection.Reason.remote_json);
        }
        JsonNode code = json.get("errcode");
        if (code == null || !code.isIntegralNumber()) {
            throw new Rejection(503, Rejection.Reason.remote_json);
        }
        if (!code.bigIntegerValue().equals(java.math.BigInteger.ZERO)) {
            throw new Rejection(503, Rejection.Reason.remote_business);
        }
    }
}
