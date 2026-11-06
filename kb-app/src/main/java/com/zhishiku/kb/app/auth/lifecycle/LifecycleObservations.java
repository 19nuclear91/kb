package com.zhishiku.kb.app.auth.lifecycle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 接收、持久访问限制和会话清理分别观测；未确认的记录不产生耗时样本。 */
@Component
public class LifecycleObservations {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleObservations.class);
    public enum Dependency {
        SAMPLE, MEMBER, EVIDENCE
    }
    public enum Cleanup {
        PAGE, DONE, FAILED
    }

    private final MeterRegistry registry;
    private final AtomicLong lastReceived = new AtomicLong();

    public LifecycleObservations(MeterRegistry registry) {
        this.registry = registry;
        for (var reason : DingTalkInputRejected.Reason.values()) {
            registry.counter("kb.lifecycle.input_rejections", "source", "dingtalk", "reason", reason.name());
        }
        for (String outcome : new String[]{"COMMITTED", "DUPLICATE", "CONFLICT", "FAILED"}) {
            registry.counter("kb.lifecycle.reception", "source", "dingtalk", "outcome", outcome);
        }
        Gauge.builder("kb.lifecycle.last_received_timestamp_seconds", lastReceived, value -> value.get() / 1000.0)
                .tag("source", "dingtalk").register(registry);
    }

    public void rejected(DingTalkInputRejected.Reason reason) {
        safely(() -> {
            registry.counter("kb.lifecycle.input_rejections", "source", "dingtalk", "reason", reason.name()).increment();
            LOG.warn("Lifecycle input rejected category={}", reason.name());
        });
    }

    /** 只能在接收事务 afterCommit 调用。数据库 recordedAt 不是这个时刻。 */
    public void received(LifecycleReception.Result result, Instant committedAt) {
        safely(() -> {
            lastReceived.set(committedAt.toEpochMilli());
            String outcome = result.conflicted() ? "CONFLICT" : result.duplicate() ? "DUPLICATE" : "COMMITTED";
            registry.counter("kb.lifecycle.reception", "source", "dingtalk", "outcome", outcome).increment();
            if (!result.duplicate() && !result.conflicted()) {
                applied(result, true);
            }
        });
    }

    /** 补映射、原事件恢复也只有在其外层事务提交后才记录限制结果。 */
    public void applied(LifecycleReception.Result result) {
        applied(result, false);
    }

    private void applied(LifecycleReception.Result result, boolean sameReceptionCommit) {
        safely(() -> {
            if (result.conflicted()) {
                return;
            }
            registry.counter("kb.lifecycle.items", "source", "dingtalk", "outcome", "REVIEW").increment(result.review());
            registry.counter("kb.lifecycle.items", "source", "dingtalk", "outcome", "UNMAPPED").increment(result.unmapped());
            if (result.restricted() > 0) {
                registry.counter("kb.lifecycle.items", "source", "dingtalk", "outcome", "OFFBOARDED").increment(result.restricted());
                // 已匹配用户的限制与接收同事务可见，确认其提交后，持久门禁延迟为零。
                // 此样本不代表 AI 输出已停止，也不代表 Redis 清理已完成。
                if (sameReceptionCommit) {
                    registry.timer("kb.lifecycle.access_barrier_delay", "source", "dingtalk").record(0, TimeUnit.NANOSECONDS);
                }
            }
        });
    }

    public void receptionFailed() {
        safely(() -> registry.counter("kb.lifecycle.reception", "source", "dingtalk", "outcome", "FAILED").increment());
    }

    public void dependencyFailed(Dependency dependency) {
        safely(() -> registry.counter("kb.lifecycle.dependency_failures", "operation", dependency.name()).increment());
    }

    public void cleanup(Cleanup outcome) {
        safely(() -> registry.counter("kb.identity.session_cleanup", "outcome", outcome.name()).increment());
    }

    private static void safely(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // 观测故障不能把已提交的事实改成失败或延长输出资格。
        }
    }
}
