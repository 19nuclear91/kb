package com.zhishiku.kb.ai.auth;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 只观测本地许可停止；没有跨服务关联证据时不发布“离职到停止”耗时。 */
@Component
public class OutputPermitMetrics {
    private final MeterRegistry registry;
    private final LongSupplier nanos;
    private final Set<Handle> active = ConcurrentHashMap.newKeySet();

    @Autowired
    public OutputPermitMetrics(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    public OutputPermitMetrics(MeterRegistry registry, LongSupplier nanos) {
        this.registry = registry;
        this.nanos = nanos;
        Gauge.builder("kb.ai.output_permit.active", active, Set::size).register(registry);
        Gauge.builder("kb.ai.output_permit.overdue_seconds", this, metrics -> metrics.overdue()).register(registry);
    }

    public Handle begin(long started, long deadline) {
        Handle handle = new Handle(started, deadline);
        active.add(handle);
        return handle;
    }

    private double overdue() {
        long now = nanos.getAsLong();
        return active.stream().mapToDouble(handle -> Math.max(0, now - handle.deadline.get()) / 1_000_000_000.0).max().orElse(0);
    }

    public void acquisitionFailed(String code) {
        safely(() -> registry.counter("kb.ai.output_permit.acquisition_failures", "reason", reason(code)).increment());
    }

    private static String reason(String code) {
        return switch (code) {
            case "ACCOUNT_REVOKED", "AUTHENTICATION_UNAVAILABLE", "OUTPUT_PERMIT_EXPIRED" -> code;
            default -> "AUTHENTICATION_UNAVAILABLE";
        };
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 监控异常不能延后或撤回访问撤销。
        }
    }

    public final class Handle {
        private final long started;
        private final long budget;
        private final AtomicLong deadline;
        private final AtomicBoolean recorded = new AtomicBoolean();

        private Handle(long started, long deadline) {
            this.started = started;
            budget = deadline - started;
            this.deadline = new AtomicLong(deadline);
        }

        public void refreshed(long nextDeadline) {
            deadline.set(nextDeadline);
        }

        public void stopped(String code) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            active.remove(this);
            safely(() -> {
                long now = nanos.getAsLong();
                registry.counter("kb.ai.output_permit.stops", "reason", reason(code)).increment();
                registry.timer("kb.ai.output_permit.observed_age", "reason", reason(code)).record(Math.max(0, now - started), TimeUnit.NANOSECONDS);
                long overdue = Math.max(0, now - deadline.get());
                registry.timer("kb.ai.output_permit.stop_overdue", "reason", reason(code)).record(overdue, TimeUnit.NANOSECONDS);
                if ("ACCOUNT_REVOKED".equals(code)) {
                    // 最近一次成功检查的请求开始早于其后出现的撤销事实；只提供保守上界，不冒充接收时刻。
                    long sinceVerifiedRequest = Math.max(0, now - (deadline.get() - budget));
                    registry.timer("kb.ai.output_permit.revocation_stop_upper_bound").record(sinceVerifiedRequest, TimeUnit.NANOSECONDS);
                }
                if (overdue > 0) {
                    registry.counter("kb.ai.output_permit.deadline_misses").increment();
                }
            });
        }

        public void closed() {
            active.remove(this);
        }
    }
}
