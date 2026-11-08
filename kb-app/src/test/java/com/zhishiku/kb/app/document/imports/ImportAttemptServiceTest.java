package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportAttemptService;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportAttemptServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-08T00:00:00Z");
    private final AtomicReference<Item> current = new AtomicReference<>();
    private final AtomicLong attempts = new AtomicLong();
    private ImportAttemptService service;

    @BeforeEach
    void setup() {
        var actor = new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false);
        when(access.current("token")).thenReturn(actor);
        when(access.requireUpload("token", 8)).thenReturn(actor);
        when(mapper.batch(1, 9)).thenReturn(new Batch(9, 1, "create", "a".repeat(64), 8, (short) 0, 2, 10, now));
        current.set(item(2, ItemState.PENDING, 0));
        when(mapper.lockItem(anyLong())).thenAnswer(call -> current.get());
        when(mapper.now()).thenReturn(now);
        when(mapper.nextAttemptId()).thenAnswer(call -> attempts.incrementAndGet());
        when(mapper.insertAttempt(any())).thenReturn(1);
        when(mapper.claim(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            Item old = current.get();
            current.set(item(old.id(), ItemState.PROCESSING, old.revision() + 1));
            return 1;
        });
        var properties = new ImportProperties("100", "1073741824", "8", "2", "120", "60", "30000", "100");
        service = new ImportAttemptService(mapper, access, properties, new LockedTransactions());
    }

    @Test
    void createsIndependentObjectKeysEvenForIdenticalContents() {
        var first = service.begin("token", 9, 2, "first", 0);
        current.set(item(3, ItemState.PENDING, 0));
        var second = service.begin("token", 9, 3, "second", 0);
        assertThat(first.attempt().objectKey()).startsWith("documents/imports/").endsWith(".txt");
        assertThat(first.attempt().objectKey()).isNotEqualTo(second.attempt().objectKey());
        assertThat(first.attempt().leaseUntil()).isEqualTo(now.plusSeconds(120));
        assertThat(first.attempt().authEpoch()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsOnlyAcquireOneExecution() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> invoke(start, "one"));
            var two = executor.submit(() -> invoke(start, "two"));
            start.countDown();
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("started", "IMPORT_CONFLICT");
        }
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void invalidatedRequestCannotRestartEvenWithMatchingRevision() {
        current.set(item(2, ItemState.FAILED, 2));
        String binding = ImportInput.digest(List.of(1L, 9L, 2L, 2L, current.get().file()));
        when(mapper.attemptByRequest(2, "old")).thenReturn(new Attempt(5, 2, "old", binding, 1,
                "documents/imports/old.txt", AttemptState.INVALIDATED, 2, now, now.plusSeconds(120), now.plusSeconds(121)));
        assertThatThrownBy(() -> service.begin("token", 9, 2, "old", 2)).hasMessage("IMPORT_CONFLICT");
        assertThat(service.begin("token", 9, 2, "new", 2).attempt().generation()).isEqualTo(3);
    }

    @Test
    void staleRevisionAndForeignBatchAreRejected() {
        assertThatThrownBy(() -> service.begin("token", 9, 2, "key", 8)).hasMessage("IMPORT_CONFLICT");
        assertThatThrownBy(() -> service.begin("token", 10, 2, "key", 0)).hasMessage("IMPORT_NOT_FOUND");
        assertThat(attempts.get()).isZero();
    }

    @Test
    void acceptedReceiptSurvivesOldRevisionAndNewRequestWithoutNewExecution() {
        Item original = current.get();
        current.set(new Item(2, 9, original.itemKey(), original.ordinal(), original.fileName(), original.title(),
                original.sizeBytes(), original.sha256(), ItemState.ACCEPTED, 2, 3L, 10L, 20L, 30L, null));
        assertThat(service.begin("token", 9, 2, "new-request", 0).receipt()).isEqualTo(new Receipt(10, 20, 30));
        assertThat(attempts.get()).isZero();
    }

    private String invoke(CountDownLatch start, String key) throws Exception {
        start.await();
        try {
            service.begin("token", 9, 2, key, 0);
            return "started";
        } catch (RuntimeException failed) {
            return failed.getMessage();
        }
    }

    private Item item(long id, ItemState state, long revision) {
        return new Item(id, 9, "item-" + id, (int) id, "x.txt", "标题", 5, "a".repeat(64), state,
                revision, revision == 0 ? null : 1L, null, null, null, state == ItemState.FAILED ? FailureCode.EXPIRED : null);
    }

    private final class LockedTransactions implements PlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private Item snapshot;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            lock.lock();
            snapshot = current.get();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            lock.unlock();
        }

        @Override
        public void rollback(TransactionStatus status) {
            current.set(snapshot);
            lock.unlock();
        }
    }
}
