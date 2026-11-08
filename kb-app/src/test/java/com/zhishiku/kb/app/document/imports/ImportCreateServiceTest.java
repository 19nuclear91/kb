package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Created;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Manifest;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportCreateService;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内存事务替身验证提交与回滚协作，不模拟 PostgreSQL 锁的真实性能。 */
class ImportCreateServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private final SecurityLevelDictionary levels = mock(SecurityLevelDictionary.class);
    private final MemoryTransaction transactions = new MemoryTransaction();
    private ImportCreateService service;
    private boolean failSecond;

    @BeforeEach
    void setup() {
        var actor = new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false);
        when(access.requireUpload("token", 9)).thenReturn(actor);
        when(access.current("token")).thenReturn(actor);
        when(levels.contains(anyShort())).thenReturn(true);
        when(mapper.shareSpace(9)).thenReturn(9L);
        AtomicLong ids = new AtomicLong();
        when(mapper.nextBatchId()).thenAnswer(call -> ids.incrementAndGet());
        when(mapper.insertBatch(any())).thenAnswer(call -> {
            Batch batch = call.getArgument(0);
            String key = batch.ownerUserId() + ":" + batch.createRequestId();
            return transactions.batches.putIfAbsent(key, batch) == null ? 1 : 0;
        });
        when(mapper.byRequest(anyLong(), anyString())).thenAnswer(call -> transactions.batches.get(
                call.getArgument(0) + ":" + call.getArgument(1)));
        when(mapper.insertItem(anyLong(), anyInt(), any())).thenAnswer(call -> {
            if (failSecond && (int) call.getArgument(1) == 2) {
                throw new IllegalStateException("injected write failure");
            }
            transactions.files.add(call.getArgument(2));
            return 1;
        });
        service = new ImportCreateService(mapper, access, levels, transactions);
    }

    @Test
    void replayReturnsOriginalBatchAndDoesNotResetItems() {
        Created first = service.create("token", "request", manifest("a"));
        Created replay = service.create("token", " request ", manifest("a"));
        assertThat(replay.importId()).isEqualTo(first.importId());
        assertThat(replay.replayed()).isTrue();
        assertThat(transactions.files).hasSize(2);
        assertThatThrownBy(() -> service.create("token", "request", manifest("b"))).hasMessage("IMPORT_CONFLICT");
        assertThat(transactions.files).hasSize(2);
    }

    @Test
    void failedItemWriteRollsBackBatchAndEarlierItems() {
        failSecond = true;
        assertThatThrownBy(() -> service.create("token", "request", manifest("a"))).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.batches).isEmpty();
        assertThat(transactions.files).isEmpty();
        assertThat(transactions.rollbacks).isEqualTo(1);
        failSecond = false;
        assertThat(service.create("token", "request", manifest("a")).replayed()).isFalse();
    }

    @Test
    void concurrentMatchingRequestsConvergeInControlledRepository() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                start.await();
                return service.create("token", "request", manifest("a"));
            });
            var two = pool.submit(() -> {
                start.await();
                return service.create("token", "request", manifest("a"));
            });
            start.countDown();
            var first = one.get(5, TimeUnit.SECONDS);
            var second = two.get(5, TimeUnit.SECONDS);
            assertThat(first.importId()).isEqualTo(second.importId());
            assertThat(first.replayed()).isNotEqualTo(second.replayed());
        }
        assertThat(transactions.files).hasSize(2);
    }

    @Test
    void newBatchIsANewIntentEvenWithSameContents() {
        assertThat(service.create("token", "one", manifest("a")).importId())
                .isNotEqualTo(service.create("token", "two", manifest("a")).importId());
        assertThat(transactions.files).hasSize(4);
    }

    @Test
    void resolveIsReadOnlyAndBoundToCurrentOwner() {
        var created = service.create("token", "request", manifest("a"));
        clearInvocations(mapper);
        assertThat(service.resolve("token", "request").importId()).isEqualTo(created.importId());
        when(access.current("other")).thenReturn(new EpochSessionService.Session(2, 1, EpochSessionService.Method.LOCAL, false));
        assertThat(service.resolve("other", "request").found()).isFalse();
        verify(mapper, never()).insertBatch(any());
        verify(mapper, never()).insertItem(anyLong(), anyInt(), any());
    }

    @Test
    void replayAndNewCreationRequireCurrentUploadPermission() {
        service.create("token", "request", manifest("a"));
        when(access.requireUpload("token", 9)).thenThrow(new ImportException(ImportException.Reason.FORBIDDEN));
        assertThatThrownBy(() -> service.create("token", "request", manifest("a"))).hasMessage("IMPORT_FORBIDDEN");
        assertThat(transactions.files).hasSize(2);
    }

    @Test
    void archivedSpaceAndUnknownSecurityLevelPreventCreation() {
        when(mapper.shareSpace(9)).thenReturn(null);
        assertThatThrownBy(() -> service.create("token", "request", manifest("a"))).hasMessage("IMPORT_FORBIDDEN");
        when(mapper.shareSpace(9)).thenReturn(9L);
        when(levels.contains((short) 0)).thenReturn(false);
        assertThatThrownBy(() -> service.create("token", "request", manifest("a"))).hasMessage("IMPORT_INVALID_INPUT");
        assertThat(transactions.batches).isEmpty();
    }

    private Manifest manifest(String digest) {
        return new Manifest(9, (short) 0, List.of(new FileIntent("one", "x.txt", "标题", 5, "a".repeat(64)),
                new FileIntent("two", "x.txt", "标题", 5, "a".repeat(64))), 10, digest.repeat(64));
    }

    private static final class MemoryTransaction implements PlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private Map<String, Batch> batches = new HashMap<>();
        private List<FileIntent> files = new ArrayList<>();
        private Map<String, Batch> oldBatches;
        private List<FileIntent> oldFiles;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            lock.lock();
            oldBatches = new HashMap<>(batches);
            oldFiles = new ArrayList<>(files);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            lock.unlock();
        }

        @Override
        public void rollback(TransactionStatus status) {
            batches = oldBatches;
            files = oldFiles;
            rollbacks++;
            lock.unlock();
        }
    }
}
