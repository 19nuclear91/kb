package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.embedding.EmbeddingProtocolFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 只允许固定枚举标签，不接受用户、请求或供应商字符串。 */
@Component
public class SearchMetrics {
    public enum Operation {
        SEARCH, CATEGORIES, TAGS
    }

    public enum Outcome {
        SUCCESS, EMPTY, FAILED, REJECTED, CANCELLED
    }

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void bind(SearchAdmission admission, SearchExecutor executor) {
        if (registry != null) {
            registry.gauge("kb.search.inflight", admission, SearchAdmission::inflight);
            registry.gauge("kb.search.lingering", executor, SearchExecutor::lingering);
        }
    }

    public void request(Outcome outcome) {
        request(Operation.SEARCH, outcome);
    }

    public void request(Operation operation, Outcome outcome) {
        if (registry != null) {
            if (operation == Operation.SEARCH) {
                registry.counter("kb.search.requests", "outcome", outcome.name()).increment();
            } else {
                registry.counter("kb.search.options.requests", "operation", operation.name(), "outcome", outcome.name()).increment();
            }
        }
    }

    public void rejected(SearchFailure.Kind reason) {
        rejected(Operation.SEARCH, reason);
    }

    public void rejected(Operation operation, SearchFailure.Kind reason) {
        if (registry != null && (reason == SearchFailure.Kind.USER_LIMIT || reason == SearchFailure.Kind.CAPACITY)) {
            if (operation == Operation.SEARCH) {
                registry.counter("kb.search.rejections", "reason", reason.name()).increment();
            } else {
                registry.counter("kb.search.options.rejections", "operation", operation.name(), "reason", reason.name()).increment();
            }
        }
    }

    public void stage(SearchBudget.Stage stage, Throwable failure, long nanos) {
        stage(Operation.SEARCH, stage, failure, nanos);
    }

    public void stage(SearchMetrics.Operation operation, SearchBudget.Stage stage, Throwable failure, long nanos) {
        String outcome = "SUCCESS";
        if (failure instanceof SearchStageFailure known) {
            outcome = known.reason().name();
        } else if (failure instanceof EmbeddingProtocolFailure) {
            outcome = "INVALID_RESPONSE";
        } else if (failure instanceof SearchFailure known) {
            outcome = known.kind().name();
        } else if (failure != null) {
            outcome = "FAILED";
        }
        if (registry != null) {
            if (operation == Operation.SEARCH) {
                registry.timer("kb.search.stage.duration", "stage", stage.name(), "outcome", outcome)
                        .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
            } else {
                registry.timer("kb.search.options.stage.duration", "operation", operation.name(), "stage", stage.name(), "outcome", outcome)
                        .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
            }
        }
    }

    public void degradation(SearchBudget.Stage stage, SearchStageFailure.Reason reason) {
        if (registry != null) {
            registry.counter("kb.search.degradation", "stage", stage.name(), "reason", reason.name()).increment();
        }
    }
}
