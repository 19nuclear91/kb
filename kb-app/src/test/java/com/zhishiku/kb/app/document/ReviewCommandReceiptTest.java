package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.collaboration.mapper.CommandReceiptMapper;
import com.zhishiku.kb.app.collaboration.model.CommandReceiptRow;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewCommandReceiptTest {
    @Test
    void concurrentRepositorySubstituteAcknowledgesOneWriteAndReadsOnlyAfterConflictCompletes() throws Exception {
        var mapper = mock(CommandReceiptMapper.class);
        var rows = new ConcurrentHashMap<ReviewCommandBinding, CommandReceiptRow>();
        var ready = new CompletableFuture<Void>();
        var writeCount = new AtomicInteger();
        var binding = ReviewCommandBinding.policy("same", 7, 3, 8, PublicationPolicy.AUTO);
        when(mapper.claim(any())).thenAnswer(call -> {
            var inserted = rows.putIfAbsent(binding, row(binding));
            if (inserted == null) return 1;
            ready.join();
            return 0;
        });
        when(mapper.find(any())).thenAnswer(call -> rows.get(binding));
        when(mapper.complete(any(), any())).thenAnswer(call -> {
            rows.get(binding).setReceipt(call.getArgument(1));
            ready.complete(null);
            return 1;
        });
        var service = new CommandReceiptService(mapper, new ObjectMapper());
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                String receipt = service.claim(binding);
                if (receipt != null) return receipt;
                writeCount.incrementAndGet();
                service.complete(binding, Map.of("version", "9"));
                return "{\"version\":\"9\"}";
            }));
            for (var future : futures) assertThat(future.get()).isEqualTo("{\"version\":\"9\"}");
        }
        assertThat(writeCount.get()).isEqualTo(1);
        // 该替身模拟仓储协议，不声称验证 PostgreSQL 的唯一约束阻塞或锁互斥。
    }

    @Test
    void differentActorPayloadTargetOrExpectedVersionCannotObtainOriginalReceipt() {
        var mapper = mock(CommandReceiptMapper.class);
        var first = ReviewCommandBinding.policy("id", 7, 3, 8, PublicationPolicy.AUTO);
        var stored = row(first);
        stored.setReceipt("{\"version\":\"9\"}");
        when(mapper.claim(any())).thenReturn(0);
        when(mapper.find(any())).thenReturn(stored);
        var service = new CommandReceiptService(mapper, new ObjectMapper());
        for (var changed : new ReviewCommandBinding[]{
                ReviewCommandBinding.policy("id", 8, 3, 8, PublicationPolicy.AUTO),
                ReviewCommandBinding.policy("id", 7, 4, 8, PublicationPolicy.AUTO),
                ReviewCommandBinding.policy("id", 7, 3, 9, PublicationPolicy.AUTO),
                ReviewCommandBinding.policy("id", 7, 3, 8, PublicationPolicy.REVIEW_REQUIRED)}) {
            assertThatThrownBy(() -> service.claim(changed)).isInstanceOf(ApiException.class).hasMessage("STATE_CONFLICT");
        }
        assertThat(service.claim(first)).isEqualTo(stored.getReceipt());
    }

    @Test
    void conflictReadIsSeparateStatementAndMissingOrIncompleteReceiptFailsClosed() {
        var mapper = mock(CommandReceiptMapper.class);
        var binding = ReviewCommandBinding.policy("id", 7, 3, 8, PublicationPolicy.AUTO);
        when(mapper.claim(any())).thenReturn(0);
        var service = new CommandReceiptService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> service.claim(binding)).isInstanceOf(ApiException.class);
        var order = inOrder(mapper);
        order.verify(mapper).claim(binding);
        order.verify(mapper).find(binding);
        when(mapper.find(any())).thenReturn(row(binding));
        assertThatThrownBy(() -> service.claim(binding)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.read("null", Object.class)).isInstanceOf(ApiException.class);
    }

    private static CommandReceiptRow row(ReviewCommandBinding binding) {
        var row = new CommandReceiptRow();
        row.setOperationDomain(binding.domain());
        row.setRequestId(binding.requestId());
        row.setActorUserId(binding.actorUserId());
        row.setSpaceId(binding.spaceId());
        row.setDocId(binding.docId());
        row.setVersionId(binding.versionId());
        row.setReviewId(binding.reviewId());
        row.setExpectedVersion(binding.expectedVersion());
        row.setInputDigest(binding.inputDigest());
        return row;
    }
}
