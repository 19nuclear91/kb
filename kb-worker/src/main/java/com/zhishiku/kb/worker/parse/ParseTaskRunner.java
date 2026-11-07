package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.CallBudget;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.telemetry.TaskContextPropagation;
import com.zhishiku.kb.worker.telemetry.TaskTelemetry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务领取与执行循环。
 *
 * <p><b>P0 有意做成单线程顺序处理</b>：一个 Worker 实例同时只处理一个任务。
 * 单实例限制第三方解析调用、分块和向量化的在途资源；
 * {@code FOR UPDATE SKIP LOCKED} 支持多个 Worker 分担任务。
 * 调整并发前需测量第三方服务配额、单任务内存与索引发布耗时。
 *
 * <p>启动一个实例前提：{@code kb-app} 已完成 Flyway 迁移。Worker 不建表。
 */
@Component
public class ParseTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(ParseTaskRunner.class);

    private final ParseTaskMapper mapper;
    private final ParseTaskClaimService claims;
    private final ParsePipeline pipeline;
    private final ChunkPublisher publisher;
    private final ParseProperties props;
    private final TaskFailureAlert failureAlert;
    private final FailureClassifier failureClassifier;
    private final ParseTaskRecoveryService recoveryService;
    private final ParseTaskMetrics metrics;
    private final TaskContextPropagation contexts;
    private final WorkerExecutions executions;
    private boolean ownsExecutions;
    private volatile boolean closed;
    private TaskTelemetry telemetry = TaskTelemetry.NOOP;

    @Autowired
    public void setTelemetry(TaskTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 本实例标识。出现"任务卡在 running"时靠它定位是哪台机器。
     */
    private final String owner;

    /**
     * 当前持有的任务，供心跳续租使用。null 表示空闲。
     */
    private final AtomicReference<Execution> inFlightTask = new AtomicReference<>();

    /**
     * 执行中的解析任务及其租约持有标记。
     *
     * @param task      当前解析任务
     * @param leaseHeld 当前执行是否仍持有租约的共享标记
     */
    private record Execution(ParseTask task, AtomicBoolean leaseHeld, WorkerExecution budget) {
    }

    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline,
                           ChunkPublisher publisher, ParseProperties props,
                           TaskFailureAlert failureAlert,
                           FailureClassifier failureClassifier,
                           ParseTaskRecoveryService recoveryService,
                           ParseTaskMetrics metrics, ParseTaskClaimService claims, TaskContextPropagation contexts) {
        this(mapper, pipeline, publisher, props, failureAlert, failureClassifier, recoveryService, metrics, claims,
                contexts, new WorkerExecutions(WorkerEmbeddingSettings.defaults()));
        ownsExecutions = true;
    }

    @Autowired
    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline, ChunkPublisher publisher, ParseProperties props,
                    TaskFailureAlert failureAlert, FailureClassifier failureClassifier, ParseTaskRecoveryService recoveryService,
                    ParseTaskMetrics metrics, ParseTaskClaimService claims, TaskContextPropagation contexts, WorkerExecutions executions) {
        this.mapper = mapper;
        this.claims = claims;
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.props = props;
        this.failureAlert = failureAlert;
        this.failureClassifier = failureClassifier;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.contexts = contexts;
        this.executions = executions;
        this.owner = buildOwnerId();
        log.info("Worker 实例标识 owner={}", owner);
    }

    /**
     * 兼容直接构造 Worker runner 的测试和嵌入式调用方。
     */
    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline, ChunkPublisher publisher, ParseProperties props,
                           TaskFailureAlert failureAlert, FailureClassifier failureClassifier,
                           ParseTaskRecoveryService recoveryService, ParseTaskMetrics metrics, ParseTaskClaimService claims) {
        this(mapper, pipeline, publisher, props, failureAlert, failureClassifier, recoveryService, metrics, claims,
                new TaskContextPropagation(ObservationRegistry.NOOP, Tracer.NOOP));
    }

    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline,
                           ChunkPublisher publisher, ParseProperties props,
                           TaskFailureAlert failureAlert,
                           FailureClassifier failureClassifier,
                           ParseTaskRecoveryService recoveryService, ParseTaskClaimService claims) {
        this(mapper, pipeline, publisher, props, failureAlert, failureClassifier,
                recoveryService, new ParseTaskMetrics(), claims);
    }

    @Scheduled(fixedDelayString = "${kb.worker.poll-interval-ms}")
    public WorkerExecutions.Ticket poll() {
        if (closed) {
            return null;
        }
        return executions.submit(WorkerExecution.Purpose.PARSE, budget -> {
            try {
                if (closed || budget.stopped()) {
                    return;
                }
                List<ParseTask> tasks = claims.claim(owner, props.leaseSeconds(), 1);
                if (tasks.isEmpty()) {
                    return;
                }
                if (tasks.size() != 1) {
                    throw new IllegalStateException("parse claim exceeds execution capacity");
                }
                metrics.claimed(1);
                runOne(tasks.getFirst(), budget);
            } catch (Exception e) {
                log.error("轮询任务失败，下个周期重试");
            }
        });
    }

    private void runOne(ParseTask task, WorkerExecution budget) {
        try (var span = telemetry.start(task)) {
            runOne(task, span, budget);
        }
    }

    private void runOne(ParseTask task, TaskTelemetry.TaskSpan span, WorkerExecution budget) {
        Execution execution = new Execution(task, new AtomicBoolean(true), budget);
        inFlightTask.set(execution);
        if (closed) {
            budget.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
        }
        long startedAt = System.currentTimeMillis();
        var timer = metrics.startTimer();

        try {
            log.debug("执行已领取任务 taskId={} versionId={}", task.id(), task.versionId());
            ParsePipeline.ParsedDocument parsed = runPipeline(task, execution);
            budget.succeed();

            if (!execution.leaseHeld().get()) {
                span.failed(failureClassifier.classify(new LeaseLostException()));
                // 租约在处理期间失守，说明任务已被其他实例领走。
                // 此时不能标成功：我们的结果可能只写了一半，
                // 而新持有者正在重做。让新持有者去写终态。
                log.warn("租约已失守，放弃写回结果 taskId={} versionId={}", task.id(), task.versionId());
                return;
            }

            if (!telemetry.inStage(TaskTelemetry.Stage.PUBLISH, () -> publisher.publish(task, owner, parsed))) {
                span.failed(failureClassifier.classify(new LeaseLostException()));
                log.warn("发布结果时租约已被回收 taskId={} versionId={}", task.id(), task.versionId());
                return;
            }
            log.info("解析完成 taskId={} versionId={} 耗时={}ms",
                    task.id(), task.versionId(), System.currentTimeMillis() - startedAt);
            metrics.completed();

        } catch (LeaseLostException e) {
            budget.stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            span.failed(failureClassifier.classify(e));
            log.warn("租约失守，放弃任务 taskId={} versionId={}", task.id(), task.versionId());
            metrics.leaseLost();
            try {
                recoveryService.recordLeaseLost(task, owner);
            } catch (RuntimeException eventFailure) {
                log.warn("记录租约失守事件失败 taskId={}", task.id(), eventFailure);
            }
        } catch (Exception e) {
            Exception failure = budget.stopped() ? new WorkerEmbeddingFailure(budget.reason()) : e;
            budget.stop(failure instanceof WorkerEmbeddingFailure known
                    ? known.reason() : WorkerEmbeddingFailure.Reason.SHUTDOWN);
            span.failed(failureClassifier.classify(failure));
            if (!(failure instanceof WorkerEmbeddingFailure known && known.controlStop())) {
                handleFailure(task, failure);
            }
        } finally {
            inFlightTask.compareAndSet(execution, null);
            execution.leaseHeld().set(false);
            metrics.observe(timer);
        }
    }

    private void handleFailure(ParseTask task, Exception e) {
        FailureClassifier.Classification classification = failureClassifier.classify(e);
        if (classification.leaseLost()) {
            log.warn("租约失守，跳过失败写回 taskId={} versionId={}", task.id(), task.versionId());
            return;
        }
        boolean lastChance = task.attempts() >= task.maxAttempts();
        boolean deadLetter = classification.permanent() || lastChance;
        log.error("解析失败 taskId={} versionId={} attempts={}/{} class={} code={} deadLetter={}",
                task.id(), task.versionId(), task.attempts(), task.maxAttempts(),
                classification.metricClass(), classification.code(), deadLetter);

        if (e instanceof ParseFailureException parseFailure) {
            try {
                publisher.recordFailure(task, owner, parseFailure.parsed());
            } catch (Exception recordFailure) {
                log.warn("保存解析问题失败 taskId={}", task.id(), recordFailure);
            }
        }
        try {
            boolean changed;
            if (deadLetter) {
                changed = recoveryService.deadLetter(task, owner, classification);
                if (changed) {
                    metrics.deadLettered(classification.metricClass());
                }
            } else {
                long delay = props.retryDelayMillis(task.attempts(),
                        ThreadLocalRandom.current().nextDouble(-1d, 1d));
                changed = recoveryService.scheduleRetry(task, owner, classification,
                        OffsetDateTime.now(ZoneOffset.UTC).plusNanos(delay * 1_000_000L));
                if (changed) {
                    metrics.retried(classification.metricClass());
                }
            }
            if (!changed) {
                log.warn("失败写回时租约已失守 taskId={} versionId={}", task.id(), task.versionId());
            }
        } catch (Exception writeBackFailure) {
            // 写回失败不能再抛，否则整批后续任务都不会被处理。
            // 任务会因租约到期被重投，这是设计允许的路径。
            log.error("写回失败状态时出错 taskId={}，将依赖租约超时重投", task.id(), writeBackFailure);
        }
    }

    private ParsePipeline.ParsedDocument runPipeline(ParseTask task, Execution execution) throws Exception {
        var snapshot = contexts.capture();
        execution.budget().begin(Duration.ofSeconds(props.taskTimeoutSeconds()));
        try (var scope = snapshot.setThreadLocals(); var interrupt = execution.budget().interruptCurrentThread()) {
            execution.budget().check();
            return pipeline.process(task, new ParsePipeline.LeaseHandle() {
                @Override
                public boolean stillHeld() {
                    return execution.leaseHeld().get();
                }

                @Override
                public CallBudget budget() {
                    return execution.budget();
                }
            });
        }
    }

    @PreDestroy
    void shutdownPipelineExecutor() {
        closed = true;
        Execution current = inFlightTask.get();
        if (current != null) {
            current.budget().stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
        }
        if (ownsExecutions) {
            executions.close();
        }
    }

    static final class TaskTimeoutException extends RuntimeException {
        private TaskTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * 续租心跳。
     *
     * <p>与 {@link #poll()} 跑在不同线程上（{@code spring.task.scheduling.pool.size} 至少为 2），
     * 否则心跳会排在长任务后面永远得不到执行——那样续租机制等于不存在。
     */
    @Scheduled(fixedDelayString = "${kb.worker.heartbeat-ms}")
    public void heartbeat() {
        Execution execution = inFlightTask.get();
        if (execution == null || execution.budget().stopped()) {
            return;
        }
        ParseTask task = execution.task();
        try {
            if (!recoveryService.renewLease(task, owner, props.leaseSeconds())) {
                // 续租失败意味着租约已被回收（我们卡得太久，或时钟/网络出了问题）。
                // 置位让流水线尽快停手，避免与新持有者并发写同一份数据。
                log.warn("续租失败，租约已失守 taskId={}", task.id());
                execution.leaseHeld().set(false);
                execution.budget().stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            }
        } catch (Exception e) {
            execution.leaseHeld().set(false);
            execution.budget().stop(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN);
            log.warn("续租状态读取失败，停止本次执行 taskId={}", task.id());
        }
    }

    /**
     * 巡检：把重试耗尽的任务落为终态。
     *
     * <p>频率低即可。多实例同时跑这个 UPDATE 是幂等的，不需要选主。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void sweepExhausted() {
        try {
            int n = recoveryService.sweepExhausted();
            if (n > 0) {
                // 这里应接入告警：有文档永久性地进不了知识库，用户会搜不到但不知道为什么。
                log.error("有 {} 个解析任务重试耗尽，已落为 failed，需人工介入", n);
            }
        } catch (Exception e) {
            log.error("巡检超限任务失败", e);
        }
    }

    private static String buildOwnerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "/" + ManagementFactory.getRuntimeMXBean().getName();
    }
}
