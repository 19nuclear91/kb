package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingRebuildRunnerTest {
    @Test
    void heartbeatRunsWhileModelIsBusyAndLostOwnerDoesNotClaimMoreWork() throws Exception {
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var batch = new RebuildBatch(UUID.randomUUID(), 2, 3, UUID.randomUUID(), profile,
                List.of(new RebuildBatch.Item(1, 2, 3, "digest", 4, 1)));
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        when(leases.claim(any())).thenReturn(batch);
        when(leases.renew(batch)).thenReturn(true, false);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return null;
        })
                .when(processor).process(batch);
        var runner = new EmbeddingRebuildRunner(leases, processor);
        try {
            runner.tick();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            runner.tick();
            runner.tick();
            runner.tick();
            verify(leases, times(2)).renew(batch);
            verify(leases).claim(any());
            verify(processor).process(batch);
        } finally {
            finish.countDown();
            runner.close();
        }
    }

    @Test
    void noClaimMeansNoModelInvocation() {
        var leases = mock(EmbeddingLeaseService.class);
        var processor = mock(EmbeddingBatchProcessor.class);
        var runner = new EmbeddingRebuildRunner(leases, processor);
        try {
            runner.tick();
            verifyNoInteractions(processor);
        } finally {
            runner.close();
        }
    }
}
