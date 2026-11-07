package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.CallBudget;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParsePipelineBudgetTest {
    @Test
    void parsingTimeIsDeductedAndSameBudgetReachesEmbedding() throws Exception {
        var clock = new AtomicLong();
        var budget = budget(clock);
        var client = mock(ProfileEmbeddingClient.class);
        var pipeline = pipeline(client, () -> clock.set(TimeUnit.SECONDS.toNanos(899)));
        when(client.embed(anyList(), any())).thenAnswer(call -> {
            assertThat(call.getArgument(1, CallBudget.class)).isSameAs(budget);
            assertThat(budget.remainingNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(1));
            return new EmbeddingResult(ParseEmbeddingFixture.PROFILE, EmbeddingRole.DOCUMENT,
                    List.of(ParseEmbeddingFixture.vector()));
        });
        var result = pipeline.process(ParseEmbeddingFixture.task(), lease(budget));
        assertThat(result.chunks()).hasSize(1);
        verify(client, never()).embed(anyList());
    }

    @Test
    void expiredParsingCannotStartEmbeddingOrBecomeLeaseLoss() {
        var clock = new AtomicLong();
        var budget = budget(clock);
        var client = mock(ProfileEmbeddingClient.class);
        var pipeline = pipeline(client, () -> clock.set(TimeUnit.SECONDS.toNanos(900)));
        assertThatThrownBy(() -> pipeline.process(ParseEmbeddingFixture.task(), lease(budget)))
                .isInstanceOf(WorkerEmbeddingFailure.class).hasMessage("Worker embedding: TIMEOUT");
        verify(client, never()).embed(anyList(), any());
        verify(client, never()).embed(anyList());
    }

    private static WorkerExecution budget(AtomicLong clock) {
        var budget = new WorkerExecution(WorkerExecution.Purpose.PARSE, 4194304, clock::get,
                (action, nanos) -> () -> { }, Runnable::run);
        budget.begin(Duration.ofSeconds(900));
        return budget;
    }

    private static ParsePipeline.LeaseHandle lease(WorkerExecution budget) {
        return new ParsePipeline.LeaseHandle() {
            @Override
            public boolean stillHeld() {
                return true;
            }

            @Override
            public CallBudget budget() {
                return budget;
            }
        };
    }

    private static DefaultParsePipeline pipeline(ProfileEmbeddingClient client, Runnable parsing) {
        var registry = mock(EmbeddingClientRegistry.class);
        when(registry.find(anyString(), anyString())).thenReturn(Optional.of(client));
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        when(client.profile()).thenReturn(ParseEmbeddingFixture.PROFILE);
        DocumentParser parser = (input, name, mime, max) -> {
            parsing.run();
            return "body";
        };
        return new DefaultParsePipeline(key -> new ByteArrayInputStream(new byte[0]), parser,
                new IngestionProperties(1024, 1000, 100, 0), registry);
    }
}
