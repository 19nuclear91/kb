package com.zhishiku.kb.ai.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.OutputStopReport;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 输出已经取消之后才入队；有界隔离，观测拥塞不能阻塞输出定时器。 */
@Component
public class OutputStopReporter {
    private final AuthenticationClient client;
    private final MeterRegistry metrics;
    private final Executor executor;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    public OutputStopReporter(AuthenticationClient client, MeterRegistry metrics) {
        this(client, metrics, new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "kb-output-stop-evidence");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()));
    }

    public OutputStopReporter(AuthenticationClient client, MeterRegistry metrics, Executor executor) {
        this.client = client;
        this.metrics = metrics;
        this.executor = executor;
        for (String outcome : new String[]{"REPORTED", "FAILED", "DROPPED", "MISSING_ANCHOR"}) {
            metrics.counter("kb.ai.output_stop_reports", "outcome", outcome);
        }
    }

    public void submit(OutputStopReport report) {
        try {
            executor.execute(() -> send(report));
        } catch (RuntimeException full) {
            count("DROPPED");
        }
    }

    private void send(OutputStopReport report) {
        try {
            byte[] body = json.writeValueAsBytes(report);
            client.reportStop(body).retryWhen(Retry.fixedDelay(2, Duration.ofMillis(100))
                    .filter(error -> error instanceof ApiException api && api.httpStatus() == 503))
                    .block(Duration.ofSeconds(7));
            count("REPORTED");
        } catch (Exception unknown) {
            count("FAILED");
        }
    }

    public void missingAnchor() {
        count("MISSING_ANCHOR");
    }

    private void count(String outcome) {
        try {
            metrics.counter("kb.ai.output_stop_reports", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // 观测失败不得向已停止的流传播，也不得记录请求正文。
        }
    }

    @PreDestroy
    public void close() {
        if (executor instanceof ThreadPoolExecutor pool) {
            pool.shutdownNow();
        }
    }
}
