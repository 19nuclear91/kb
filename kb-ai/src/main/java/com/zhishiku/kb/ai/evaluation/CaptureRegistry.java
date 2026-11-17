package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.ai.conversation.BeginResult;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.DatasetReader;
import com.zhishiku.kb.answereval.LocalFiles;
import com.zhishiku.kb.answereval.OutputPublisher;
import com.zhishiku.kb.answereval.StrictJson;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 单实例登记索引及独立有界写入队列；关闭时不访问目录。 */
public final class CaptureRegistry implements AutoCloseable {
    private final EvaluationProperties properties;
    private final Clock clock;
    private final OutputPublisher publisher;
    private final Executor writer;
    private final ThreadPoolExecutor ownedWriter;
    private final Scheduler reader;
    private final Map<String, Long> claimed = new ConcurrentHashMap<>();
    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong storedBytes = new AtomicLong();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final long capacityLimit;

    public CaptureRegistry(EvaluationProperties properties) {
        this.properties = properties;
        clock = Clock.systemUTC();
        publisher = new OutputPublisher();
        capacityLimit = OutputPublisher.RUN_LIMIT;
        if (properties.enabled()) {
            ownedWriter = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4),
                    runnable -> {
                        Thread thread = new Thread(runnable, "answer-evaluation-writer");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            writer = ownedWriter;
            reader = Schedulers.newBoundedElastic(1, 4, "answer-evaluation-register");
            storedBytes.set(existingBytes(properties.root()));
        } else {
            ownedWriter = null;
            writer = null;
            reader = null;
        }
    }

    CaptureRegistry(EvaluationProperties properties, Clock clock, OutputPublisher publisher, Executor writer) {
        this(properties, clock, publisher, writer, OutputPublisher.RUN_LIMIT);
    }

    CaptureRegistry(EvaluationProperties properties, Clock clock, OutputPublisher publisher, Executor writer, long capacityLimit) {
        this.properties = properties;
        this.clock = clock;
        this.publisher = publisher;
        this.writer = writer;
        this.capacityLimit = Math.min(OutputPublisher.RUN_LIMIT, capacityLimit);
        ownedWriter = null;
        reader = null;
    }

    public Mono<CaptureSession> bind(BeginResult begin, String question) {
        if (!eligible(begin)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> claim(begin, question)).subscribeOn(reader)
                .timeout(Duration.ofSeconds(2)).onErrorResume(failure -> Mono.empty())
                .doOnDiscard(CaptureSession.class, CaptureSession::finish);
    }

    private boolean eligible(BeginResult begin) {
        return properties.enabled() && begin != null && !begin.replayed() && begin.turnSeq() == 1
                && begin.execution() != null && properties.allowedUsers().contains(begin.execution().ownerUserId());
    }

    CaptureSession claim(BeginResult begin, String question) {
        if (properties.enabled()) {
            sessions.values().stream().filter(session -> session.registration().expiresAtEpochMillis() <= clock.millis())
                    .forEach(CaptureSession::finish);
        }
        if (!eligible(begin) || active.incrementAndGet() > 4) {
            if (eligible(begin)) {
                active.decrementAndGet();
            }
            return null;
        }
        boolean retained = false;
        try {
            long now = clock.millis();
            claimed.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (claimed.size() >= 1000) {
                return null;
            }
            var execution = begin.execution();
            Path registrationPath = LocalFiles.child(properties.root(),
                    "registrations/" + execution.ownerUserId() + "-" + execution.conversationId() + ".json");
            if (!Files.isRegularFile(registrationPath)) {
                return null;
            }
            DataModel.Registration registration = StrictJson.read(
                    LocalFiles.read(registrationPath, StrictJson.MANIFEST_LIMIT).bytes(), DataModel.Registration.class);
            if (registration.expectedUserId() != execution.ownerUserId() || registration.conversationId() != execution.conversationId()
                    || !registration.instanceId().equals(properties.instanceId()) || registration.expiresAtEpochMillis() <= now
                    || !registration.questionSha256().equals(StrictJson.textSha(question))) {
                return null;
            }
            Path datasetPath = LocalFiles.child(properties.root(), registration.datasetFile());
            DatasetReader.Batch dataset = new DatasetReader().read(datasetPath.toString());
            if (!dataset.datasetSha256().equals(registration.datasetSha256()) || dataset.cases().stream().noneMatch(item ->
                    item.caseId().equals(registration.caseId()) && StrictJson.textSha(item.question()).equals(registration.questionSha256()))) {
                return null;
            }
            dataset.requireTarget(registration.targetId(), false);
            if (registration.expiresAtEpochMillis() <= clock.millis()
                    || claimed.putIfAbsent(registration.attemptId(), registration.expiresAtEpochMillis()) != null) {
                return null;
            }
            DataModel.Binding binding = new DataModel.Binding(registration.runId(), registration.caseId(),
                    registration.attemptId(), dataset.datasetSha256(), execution.ownerUserId(), execution.authEpoch(),
                    execution.conversationId(), execution.turnId().toString(), registration.questionSha256());
            Path claim = LocalFiles.child(properties.root(), "claims/" + registration.attemptId() + ".json");
            Files.createDirectories(claim.getParent());
            LocalFiles.checkAncestors(claim);
            Files.write(claim, StrictJson.bytes(binding), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            CaptureSession session = new CaptureSession(registration, binding, dataset, this::enqueue);
            sessions.put(registration.attemptId(), session);
            retained = true;
            return session;
        } catch (IOException | RuntimeException failure) {
            return null;
        } finally {
            if (!retained) {
                active.decrementAndGet();
            }
        }
    }

    private void enqueue(CaptureSession session) {
        sessions.remove(session.registration().attemptId());
        active.decrementAndGet();
        if (queuedCount.incrementAndGet() > 4) {
            queuedCount.decrementAndGet();
            return;
        }
        // 按每份上限预留；序列化也由独立线程承担，取消线程只投递已冻结的有界状态。
        long reservation = OutputPublisher.CAPTURE_LIMIT;
        long queued = queuedBytes.addAndGet(reservation);
        if (queued > 32L * 1024 * 1024) {
            queuedBytes.addAndGet(-reservation);
            queuedCount.decrementAndGet();
            return;
        }
        try {
            writer.execute(() -> {
                try {
                    byte[] bytes = session.payload();
                    if (bytes.length > OutputPublisher.CAPTURE_LIMIT - 1024) {
                        return;
                    }
                    if (storedBytes.addAndGet(bytes.length + 1024L) > capacityLimit) {
                        return;
                    }
                    Path target = LocalFiles.child(properties.root(), "captures/" + session.registration().artifactKey());
                    try (var output = publisher.begin(publisher.preflight(target.toString()))) {
                        output.put(session.fileName(), bytes);
                        output.finish();
                    }
                } catch (RuntimeException failure) {
                    // 原始异常不出站；缺少完整清单使运行器明确判为采集不完整。
                } finally {
                    queuedBytes.addAndGet(-reservation);
                    queuedCount.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            queuedBytes.addAndGet(-reservation);
            queuedCount.decrementAndGet();
        }
    }

    private static long existingBytes(Path root) {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var entries = Files.walk(root, 4)) {
            long total = 0;
            int count = 0;
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                LocalFiles.checkAncestors(path);
                if (++count > 20_000) {
                    return OutputPublisher.RUN_LIMIT;
                }
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                    if (total >= OutputPublisher.RUN_LIMIT) {
                        return OutputPublisher.RUN_LIMIT;
                    }
                }
            }
            return total;
        } catch (IOException | RuntimeException failure) {
            return OutputPublisher.RUN_LIMIT;
        }
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.dispose();
        }
        if (ownedWriter != null) {
            ownedWriter.shutdownNow();
        }
    }
}
