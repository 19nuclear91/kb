package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.suggestion.SuggestionHttp;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class SuggestionRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SuggestionRunner.class);
    private final SuggestionMaterials materials;
    private final SuggestionExecution execution;
    private final SuggestionStore store;
    private final SuggestionHttp ai;
    private final ObjectMapper json;
    private final ThreadPoolExecutor stops = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("metadata-suggestion-stop-", 0).factory());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("metadata-suggestion-", 0).factory());

    @Autowired
    public SuggestionRunner(SuggestionMaterials materials, SuggestionExecution execution, SuggestionStore store,
                            ObjectMapper json, @Value("${kb.metadata-suggestions.ai-base-url:}") String base,
                            @Value("${kb.metadata-suggestions.secret:}") String secret,
                            @Value("${kb.metadata-suggestions.enabled:false}") boolean enabled) {
        this(materials, execution, store, json, new SuggestionHttp(base, "kb-app", secret));
        if (enabled) {
            ai.validate();
        }
    }

    SuggestionRunner(SuggestionMaterials materials, SuggestionExecution execution, SuggestionStore store,
                     ObjectMapper json, SuggestionHttp ai) {
        this.materials = materials;
        this.execution = execution;
        this.store = store;
        this.json = json;
        this.ai = ai;
    }

    @TransactionalEventListener
    public void accept(SuggestionRequests.Start start) {
        try {
            workers.execute(() -> {
                try {
                    run(start);
                } catch (RuntimeException unavailable) {
                    LOG.warn("整理运行记录待恢复 requestId={}", start.id());
                }
            });
        } catch (RejectedExecutionException full) {
            // 离开原事务的提交回调后独立记录终态。
            finish(start, SuggestionProtocol.Outcome.failure("UNAVAILABLE"));
        }
    }

    @TransactionalEventListener
    public void stop(SuggestionRequests.Stop stop) {
        try {
            stops.execute(() -> {
                try {
                    ai.post("stop", json.writeValueAsBytes(stop.key()), null, Duration.ofSeconds(2), 64);
                } catch (Exception unavailable) {
                    // 未抵达执行节点时，该节点仍会主动核对取消事实。
                }
            });
        } catch (RejectedExecutionException busy) {
            // 终态已提交，执行节点的有界核对继续生效。
        }
    }

    private void run(SuggestionRequests.Start start) {
        var job = store.find(start.id());
        if (job == null || !job.running() || !job.executionId().equals(start.execution())) {
            return;
        }
        SuggestionProtocol.Outcome outcome;
        try {
            var material = materials.load(start.token(), job);
            var command = new SuggestionProtocol.Run(SuggestionExecution.key(job), material);
            byte[] body = json.writeValueAsBytes(command);
            if (body.length > SuggestionProtocol.MATERIAL_LIMIT) {
                throw new SuggestionMaterials.Rejection("INPUT_TOO_LARGE");
            }
            byte[] response = ai.post("run", body, start.token(), Duration.ofSeconds(120), SuggestionProtocol.RESULT_LIMIT);
            outcome = json.readValue(response, SuggestionProtocol.Outcome.class);
        } catch (RuntimeException failed) {
            outcome = SuggestionProtocol.Outcome.failure(SuggestionExecution.reason(failed));
        } catch (Exception invalid) {
            outcome = SuggestionProtocol.Outcome.failure("UNAVAILABLE");
        }
        finish(start, outcome);
    }

    private void finish(SuggestionRequests.Start start, SuggestionProtocol.Outcome outcome) {
        try {
            execution.finish(start.id(), start.execution(), start.token(), outcome);
        } catch (RuntimeException unavailable) {
            LOG.warn("整理结果写入待核对 requestId={}", start.id());
            try {
                execution.finish(start.id(), start.execution(), null,
                        new SuggestionProtocol.Outcome("UNAVAILABLE", outcome.prompt(), outcome.completion(), null));
            } catch (RuntimeException failed) {
                LOG.warn("整理额度记录待恢复 requestId={}", start.id());
            }
        }
    }

    @Scheduled(fixedDelayString = "${kb.metadata-suggestions.recovery-ms:5000}")
    public void recover() {
        try {
            recoverDue();
        } catch (RuntimeException unavailable) {
            LOG.warn("整理恢复扫描暂不可用");
        }
    }

    private void recoverDue() {
        for (UUID id : store.expiredExecutions()) {
            var job = store.find(id);
            if (job != null) {
                execution.finish(id, job.executionId(), null, SuggestionProtocol.Outcome.failure("TIMEOUT"));
            }
        }
        for (UUID id : store.pendingCharges()) {
            var job = store.find(id);
            if (job == null || !"PENDING".equals(job.settlementStatus())) {
                continue;
            }
            try {
                var charge = new SuggestionProtocol.Charge(SuggestionExecution.key(job), job.reservedTokens(), job.plannedCharge());
                var response = ai.post("settle", json.writeValueAsBytes(charge), null, Duration.ofSeconds(5), 64);
                store.settle(id, json.readValue(response, Boolean.class));
            } catch (Exception unavailable) {
                // 下次仅重试已冻结的同一结算意图。
            }
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        stops.shutdownNow();
    }
}
