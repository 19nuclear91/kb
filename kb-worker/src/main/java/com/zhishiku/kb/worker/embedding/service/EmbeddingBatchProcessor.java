package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.worker.embedding.EmbeddingRebuildProperties;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingBatchProcessor {
    private final EmbeddingBatchGenerator generator;
    private final EmbeddingBatchCommitter committer;
    private final EmbeddingFailureService failures;
    private final EmbeddingRebuildProperties props;

    public EmbeddingBatchProcessor(EmbeddingBatchGenerator generator, EmbeddingBatchCommitter committer,
                                   EmbeddingFailureService failures, EmbeddingRebuildProperties props) {
        this.generator = generator;
        this.committer = committer;
        this.failures = failures;
        this.props = props;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void process(RebuildBatch batch) {
        if (batch.items().stream().anyMatch(item -> item.attempts() > props.maxAttempts())) {
            failures.block(batch, "RETRY_EXHAUSTED");
            return;
        }
        try {
            committer.commit(generator.generate(batch));
        } catch (RebuildLeaseLostException lost) {
            // 新持有者负责后续工作，旧资格不得修改进度或失败状态。
        } catch (RebuildContentChangedException changed) {
            // 提交事务先回滚，再使用新事务阻塞；不能从共享控制锁升级为独占锁。
            failures.block(batch, "CONTENT_CHANGED");
        } catch (RuntimeException error) {
            var failure = EmbeddingRetryPolicy.classify(error);
            failures.handle(batch, failure.code(), failure.retryable(), props.maxAttempts());
        }
    }
}
