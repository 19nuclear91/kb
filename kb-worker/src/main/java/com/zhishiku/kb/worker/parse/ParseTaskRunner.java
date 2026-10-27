package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ExecutorService pipelineExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kb-worker-pipeline");
        thread.setDaemon(true);
        return thread;
    });

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
    private record Execution(ParseTask task, AtomicBoolean leaseHeld) {
    }

    @Autowired
    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline,
                           ChunkPublisher publisher, ParseProperties props,
                           TaskFailureAlert failureAlert,
                           FailureClassifier failureClassifier,
                           ParseTaskRecoveryService recoveryService,
                           ParseTaskMetrics metrics, ParseTaskClaimService claims) {
        this.mapper = mapper;
        this.claims = claims;
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.props = props;
        this.failureAlert = failureAlert;
        this.failureClassifier = failureClassifier;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.owner = buildOwnerId();
        log.info("Worker 实例标识 owner={}", owner);
    }

    /**
     * 兼容直接构造 Worker runner 的测试和嵌入式调用方。
     */
    public ParseTaskRunner(ParseTaskMapper mapper, ParsePipeline pipeline,
                           ChunkPublisher publisher, ParseProperties props,
                           TaskFailureAlert failureAlert,
                           FailureClassifier failureClassifier,
                           ParseTaskRecoveryService recoveryService, ParseTaskClaimService claims) {
        this(mapper, pipeline, publisher, props, failureAlert, failureClassifier,
                recoveryService, new ParseTaskMetrics(), claims);
    }

    @Scheduled(fixedDelayString = "${kb.worker.poll-interval-ms}")
    public void poll() {
        try {
            List<ParseTask> tasks = claims.claim(owner, props.leaseSeconds(), props.batchSize());
            if (tasks.isEmpty()) {
                return;
            }
            metrics.claimed(tasks.size());
            log.debug("领取任务 count={}", tasks.size());
            for (ParseTask task : tasks) {
                runOne(task);
            }
        } catch (Exception e) {
            // 轮询循环自身绝不能抛出去——@Scheduled 的 fixedDelay 任务一旦抛异常，
            // 后续调度仍会继续，但这里捕获是为了留下日志，
            // 否则数据库短暂不可用会表现为"Worker 静默不干活"。
            log.error("轮询任务失败，下个周期重试", e);
        }
    }

    private void runOne(ParseTask task) {
        Execution execution = new Execution(task, new AtomicBoolean(true));
        inFlightTask.set(execution);
        long startedAt = System.currentTimeMillis();
        var timer = metrics.startTimer();

        try {
            ParsePipeline.ParsedDocument parsed = runPipelineWithTimeout(task, execution.leaseHeld());

            if (!execution.leaseHeld().get()) {
                // 租约在处理期间失守，说明任务已被其他实例领走。
                // 此时不能标成功：我们的结果可能只写了一半，
                // 而新持有者正在重做。让新持有者去写终态。
                log.warn("租约已失守，放弃写回结果 taskId={} versionId={}", task.id(), task.versionId());
                return;
            }

            if (!publisher.publish(task, owner, parsed)) {
                log.warn("发布结果时租约已被回收 taskId={} versionId={}", task.id(), task.versionId());
                return;
            }
            log.info("解析完成 taskId={} versionId={} 耗时={}ms",
                    task.id(), task.versionId(), System.currentTimeMillis() - startedAt);
            metrics.completed();

        } catch (LeaseLostException e) {
            log.warn("租约失守，放弃任务 taskId={} versionId={}", task.id(), task.versionId());
            metrics.leaseLost();
            try {
                recoveryService.recordLeaseLost(task, owner);
            } catch (RuntimeException eventFailure) {
                log.warn("记录租约失守事件失败 taskId={}", task.id(), eventFailure);
            }
        } catch (Exception e) {
            handleFailure(task, e);
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
                classification.metricClass(), classification.code(), deadLetter, e);

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
                if (changed) metrics.deadLettered(classification.metricClass());
            } else {
                long delay = props.retryDelayMillis(task.attempts(),
                        ThreadLocalRandom.current().nextDouble(-1d, 1d));
                changed = recoveryService.scheduleRetry(task, owner, classification,
                        OffsetDateTime.now(ZoneOffset.UTC).plusNanos(delay * 1_000_000L));
                if (changed) metrics.retried(classification.metricClass());
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

    private ParsePipeline.ParsedDocument runPipelineWithTimeout(ParseTask task, AtomicBoolean leaseHeld) throws Exception {
        Future<ParsePipeline.ParsedDocument> future = pipelineExecutor.submit(
                () -> pipeline.process(task, leaseHeld::get));
        try {
            return future.get(props.taskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            leaseHeld.set(false);
            throw new TaskTimeoutException("解析任务超过 " + props.taskTimeoutSeconds() + " 秒");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    @PreDestroy
    void shutdownPipelineExecutor() {
        pipelineExecutor.shutdownNow();
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
        if (execution == null) {
            return;
        }
        ParseTask task = execution.task();
        try {
            if (!recoveryService.renewLease(task, owner, props.leaseSeconds())) {
                // 续租失败意味着租约已被回收（我们卡得太久，或时钟/网络出了问题）。
                // 置位让流水线尽快停手，避免与新持有者并发写同一份数据。
                log.warn("续租失败，租约已失守 taskId={}", task.id());
                execution.leaseHeld().set(false);
            }
        } catch (Exception e) {
            execution.leaseHeld().set(false);
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
