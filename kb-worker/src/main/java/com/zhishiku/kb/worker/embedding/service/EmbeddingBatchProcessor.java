package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.worker.embedding.EmbeddingRebuildProperties;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.GeneratedRebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class EmbeddingBatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchProcessor.class);
    private final EmbeddingBatchGenerator generator;
    private final EmbeddingBatchCommitter committer;
    private final EmbeddingFailureService failures;
    private final EmbeddingRebuildProperties props;
    private final WorkerEmbeddingSettings settings;

    public EmbeddingBatchProcessor(EmbeddingBatchGenerator generator, EmbeddingBatchCommitter committer,
                                   EmbeddingFailureService failures, EmbeddingRebuildProperties props) {
        this(generator, committer, failures, props, WorkerEmbeddingSettings.defaults());
    }

    @Autowired
    public EmbeddingBatchProcessor(EmbeddingBatchGenerator generator, EmbeddingBatchCommitter committer,
                                   EmbeddingFailureService failures, EmbeddingRebuildProperties props,
                                   WorkerEmbeddingSettings settings) {
        this.generator = generator;
        this.committer = committer;
        this.failures = failures;
        this.props = props;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void process(RebuildBatch batch) {
        process(batch, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public void process(RebuildBatch batch, WorkerExecution execution) {
        if (execution != null && execution.stopped()) {
            return;
        }
        if (batch.items().stream().anyMatch(item -> item.attempts() > props.maxAttempts())) {
            failures.block(batch, "RETRY_EXHAUSTED");
            return;
        }
        try {
            if (execution == null) {
                committer.commit(generator.generate(batch));
            } else {
                execution.begin(Duration.ofSeconds(settings.rebuildGenerationTimeoutSeconds()));
                GeneratedRebuildBatch generated;
                try (var interrupt = execution.interruptCurrentThread()) {
                    generated = generator.generate(batch, execution);
                }
                execution.succeed();
                committer.commit(generated);
            }
        } catch (RebuildLeaseLostException lost) {
            if (execution != null) {
                execution.stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            }
            // 新持有者负责后续工作，旧资格不得修改进度或失败状态。
        } catch (RuntimeException error) {
            if (execution != null && execution.stopped()) {
                error = new WorkerEmbeddingFailure(execution.reason());
            }
            if (execution != null) {
                execution.stop(error instanceof WorkerEmbeddingFailure known
                        ? known.reason() : WorkerEmbeddingFailure.Reason.SHUTDOWN);
            }
            if (error instanceof WorkerEmbeddingFailure known && known.controlStop()) {
                return;
            }
            if (error instanceof RebuildContentChangedException) {
                // 提交事务先回滚，再使用新事务阻塞，保留既有锁序。
                failures.block(batch, "CONTENT_CHANGED");
                return;
            }
            var failure = EmbeddingRetryPolicy.classify(error);
            if (error instanceof WorkerEmbeddingFailure known) {
                log.warn("embedding rebuild failed reason={} code={} retryable={}",
                        known.reason(), failure.code(), failure.retryable());
            }
            failures.handle(batch, failure.code(), failure.retryable(), props.maxAttempts());
        }
    }
}
