package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchCommitter;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchGenerator;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingFailureStore;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingRetryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingRetryTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final UUID runId = UUID.randomUUID();

    private RebuildBatch batch(int attempts) {
        return new RebuildBatch(runId, 4, 7, UUID.randomUUID(), profile,
                List.of(new RebuildBatch.Item(1, 2, 3, "digest", 1, attempts)));
    }

    @Test
    void classifiesRateLimitTimeoutAndPermanentErrorsWithoutRemoteBody() {
        var limited = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "secret", HttpHeaders.EMPTY, "private body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(EmbeddingRetryPolicy.classify(limited)).isEqualTo(new EmbeddingRetryPolicy.Failure(
                "MODEL_UNAVAILABLE", true));
        assertThat(EmbeddingRetryPolicy.classify(new ResourceAccessException("private timeout")))
                .isEqualTo(new EmbeddingRetryPolicy.Failure("MODEL_UNAVAILABLE", true));
        assertThat(EmbeddingRetryPolicy.classify(new IllegalStateException("invalid embedding response")))
                .isEqualTo(new EmbeddingRetryPolicy.Failure("INVALID_RESPONSE", false));
        assertThat(EmbeddingRetryPolicy.delaySeconds(1)).isEqualTo(30);
        assertThat(EmbeddingRetryPolicy.delaySeconds(2)).isEqualTo(60);
        assertThat(EmbeddingRetryPolicy.delaySeconds(20)).isEqualTo(900);
    }

    @Test
    void transientFailurePersistsBackoffThenExhaustionBlocksAndStaleFailureDoesNothing() {
        var state = mock(EmbeddingStateMapper.class);
        var leases = mock(EmbeddingLeaseService.class);
        var store = new ClockStore();
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("REBUILDING", null, null, "p",
                profile.fingerprint(), 4, runId));
        when(state.run(runId)).thenReturn(new EmbeddingRun(runId, "p", 1, "REBUILDING", 2, null, 7, null, null,
                0, 3, null, 1, 0, 0, null));
        when(leases.renew(any())).thenReturn(true);
        var service = new EmbeddingFailureService(state, leases, store);
        assertThat(service.handle(batch(1), "MODEL_UNAVAILABLE", true, 3)).isTrue();
        assertThat(store.nextAttempt).isEqualTo(store.now.plusSeconds(30));
        assertThat(store.blocked).isFalse();
        store.now = store.nextAttempt;
        assertThat(service.handle(batch(2), "MODEL_UNAVAILABLE", true, 3)).isTrue();
        assertThat(store.nextAttempt).isEqualTo(store.now.plusSeconds(60));
        assertThat(service.handle(batch(3), "MODEL_UNAVAILABLE", true, 3)).isTrue();
        assertThat(store.blocked).isTrue();
        assertThat(store.error).isEqualTo("RETRY_EXHAUSTED");
        assertThat(store.events).isEqualTo(1);
        when(leases.renew(any())).thenReturn(false);
        assertThat(service.handle(batch(1), "MODEL_UNAVAILABLE", true, 3)).isFalse();
        assertThat(store.events).isEqualTo(1);
    }

    @Test
    void processorDoesNotGenerateExhaustedItemsOrWriteBackLostLeases() {
        var generator = mock(EmbeddingBatchGenerator.class);
        var committer = mock(EmbeddingBatchCommitter.class);
        var failures = mock(EmbeddingFailureService.class);
        var processor = new EmbeddingBatchProcessor(generator, committer, failures,
                new EmbeddingRebuildProperties(300, 16, 3));
        var exhausted = batch(4);
        processor.process(exhausted);
        verify(failures).block(exhausted, "RETRY_EXHAUSTED");
        verifyNoInteractions(generator, committer);
        var lost = batch(1);
        when(generator.generate(lost)).thenThrow(new RebuildLeaseLostException());
        clearInvocations(failures);
        processor.process(lost);
        verifyNoInteractions(failures);
        doThrow(new ResourceAccessException("secret")).when(generator).generate(lost);
        processor.process(lost);
        verify(failures).handle(lost, "MODEL_UNAVAILABLE", true, 3);
    }

    private static class ClockStore extends EmbeddingFailureStore {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant nextAttempt;
        boolean blocked;
        String error;
        int events;

        ClockStore() {
            super(null);
        }

        @Override
        public int retryItems(RebuildBatch b, String e, int seconds) {
            return b.items().size();
        }

        @Override
        public int retryRun(RebuildBatch b, String e, int seconds) {
            nextAttempt = now.plusSeconds(seconds);
            return 1;
        }

        @Override
        public int failItems(RebuildBatch b, String e) {
            error = e;
            return b.items().size();
        }

        @Override
        public int blockRun(RebuildBatch b, long version, String e) {
            blocked = true;
            return 1;
        }

        @Override
        public int blockControl(RebuildBatch b) {
            return 1;
        }

        @Override
        public int blockEvent(RebuildBatch b, long version, String e) {
            events++;
            return 1;
        }
    }
}
