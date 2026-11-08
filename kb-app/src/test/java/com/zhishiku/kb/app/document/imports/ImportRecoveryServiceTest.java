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
import com.zhishiku.kb.app.document.imports.service.ImportAcceptanceService;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportFailureService;
import com.zhishiku.kb.app.document.imports.service.ImportRecoveryService;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import com.zhishiku.kb.app.storage.ListObjectsPage;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageReconciliationProperties;
import com.zhishiku.kb.app.storage.StoredObject;
import com.zhishiku.kb.app.storage.mapper.StorageReconciliationMapper;
import com.zhishiku.kb.app.storage.service.StorageReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 受控时钟及同一内存锁验证恢复/受理的合法结果，未连接数据库。 */
class ImportRecoveryServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final DocumentWriteService documents = mock(DocumentWriteService.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private final MemoryTransactions manager = new MemoryTransactions();
    private final OffsetDateTime start = OffsetDateTime.parse("2026-11-08T00:00:00Z");
    private final AtomicReference<OffsetDateTime> clock = new AtomicReference<>(start.plusSeconds(120));
    private final Attempt original = new Attempt(3, 2, "request", "a".repeat(64), 1, "documents/imports/one",
            AttemptState.ACTIVE, 2, start, start.plusSeconds(120), null);
    private final AtomicReference<Attempt> execution = new AtomicReference<>(original);
    private final AtomicReference<Item> item = new AtomicReference<>();
    private ImportRecoveryService recovery;
    private ImportAcceptanceService acceptance;
    private final AtomicReference<Item> committedItem = new AtomicReference<>();
    private final AtomicReference<Attempt> committedAttempt = new AtomicReference<>();

    @BeforeEach
    void setup() {
        item.set(row(ItemState.PROCESSING, 1, null));
        committedItem.set(item.get());
        committedAttempt.set(original);
        when(mapper.tryLockItem(2)).thenAnswer(call -> item.get());
        when(mapper.lockItem(2)).thenAnswer(call -> item.get());
        when(mapper.attempt(3)).thenAnswer(call -> execution.get());
        when(mapper.now()).thenAnswer(call -> clock.get());
        when(mapper.expired(100)).thenReturn(List.of(2L));
        when(mapper.fail(2, 3, 1, FailureCode.EXPIRED)).thenAnswer(call -> {
            item.set(row(ItemState.FAILED, 2, null));
            return 1;
        });
        when(mapper.finish(anyLong(), anyString())).thenAnswer(call -> {
            execution.set(new Attempt(3, 2, original.requestId(), original.bindingDigest(), 1, original.objectKey(),
                    AttemptState.valueOf(call.getArgument(1)), 2, start, original.leaseUntil(), clock.get()));
            return 1;
        });
        when(mapper.accept(anyLong(), anyLong(), anyLong(), any())).thenAnswer(call -> {
            if (!clock.get().isBefore(original.leaseUntil())) {
                return 0;
            }
            item.set(row(ItemState.ACCEPTED, 2, call.getArgument(3)));
            return 1;
        });
        var actor = new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false);
        when(access.current("token")).thenReturn(actor);
        when(access.requireUpload("token", 8)).thenReturn(actor);
        when(mapper.batch(1, 9)).thenReturn(new Batch(9, 1, "create", "a".repeat(64), 8, (short) 0, 1, 5, start));
        when(mapper.shareSpace(8)).thenReturn(8L);
        recovery = new ImportRecoveryService(mapper, new ImportProperties("100", "1024", "8", "2", "120", "60", "30000", "100"), manager);
        acceptance = new ImportAcceptanceService(mapper, access, documents, manager);
    }

    @Test
    void expiredAttemptIsPermanentlyInvalidatedAndLateWorkCannotCommit() {
        assertThat(recovery.recoverCycle()).isEqualTo(1);
        assertThat(item.get().revision()).isEqualTo(2);
        assertThat(item.get().failureCode()).isEqualTo(FailureCode.EXPIRED);
        assertThat(execution.get().state()).isEqualTo(AttemptState.INVALIDATED);
        assertThat(recovery.recoverCycle()).isZero();
        assertThatThrownBy(() -> acceptance.accept("token", 9, original, "text/plain")).hasMessage("IMPORT_CONFLICT");
        assertThat(new ImportFailureService(mapper, manager).invalidate(original, FailureCode.STORAGE_UNAVAILABLE)).isFalse();
        verifyNoInteractions(documents);
        verify(mapper, times(2)).expired(100);
    }

    @Test
    void earlyOrCurrentlyLockedItemsAreSkipped() {
        clock.set(start.plusSeconds(119));
        assertThat(recovery.recover(2)).isFalse();
        when(mapper.tryLockItem(2)).thenReturn(null);
        assertThat(recovery.recover(2)).isFalse();
        assertThat(execution.get().state()).isEqualTo(AttemptState.ACTIVE);
        verifyNoInteractions(documents);
    }

    @Test
    void failureToFinishRestoresActiveProtectionAndCanBeRecoveredLater() {
        doReturn(0).when(mapper).finish(3, "INVALIDATED");
        assertThat(recovery.recoverCycle()).isZero();
        assertThat(item.get().state()).isEqualTo(ItemState.PROCESSING);
        assertThat(execution.get().state()).isEqualTo(AttemptState.ACTIVE);
        verifyNoInteractions(documents);
    }

    @Test
    void receiptCommittedUnderItemLockWinsAgainstRecovery() throws Exception {
        compete(false);
        assertThat(item.get().receipt()).isEqualTo(new Receipt(10, 20, 30));
        assertThat(execution.get().state()).isEqualTo(AttemptState.ACCEPTED);
        clock.set(start.plusSeconds(121));
        assertThat(recovery.recover(2)).isFalse();
    }

    @Test
    void expiryDuringDocumentWriteRollsBackBeforeRecoveryInvalidates() throws Exception {
        compete(true);
        assertThat(item.get().state()).isEqualTo(ItemState.FAILED);
        assertThat(item.get().receipt()).isNull();
        assertThat(execution.get().state()).isEqualTo(AttemptState.INVALIDATED);
    }

    @Test
    void cleanerReadsActiveBeforeAcceptanceCommitAndVersionAfterCommit() throws Exception {
        var storage = mock(ObjectStorage.class);
        var cleaner = cleaner(storage);
        clock.set(start.plusSeconds(119));
        when(documents.create(anyLong(), anyLong(), anyString(), anyShort(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new DocumentUploadResult(10L, 20L, 30L, "pending"));
        doAnswer(call -> {
            assertThat(item.get().state()).isEqualTo(ItemState.ACCEPTED);
            assertThat(committedAttempt.get().state()).isEqualTo(AttemptState.ACTIVE);
            assertThat(cleaner.runCycle().deleted()).isZero();
            execution.set(new Attempt(3, 2, original.requestId(), original.bindingDigest(), 1, original.objectKey(),
                    AttemptState.ACCEPTED, 2, start, original.leaseUntil(), clock.get()));
            return 1;
        }).when(mapper).finish(3, "ACCEPTED");
        acceptance.accept("token", 9, original, "text/plain");
        assertThat(cleaner.runCycle().deleted()).isZero();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void cleanerWaitsForDurableInvalidationAndCollectsLateReappearingObject() throws Exception {
        var storage = mock(ObjectStorage.class);
        var cleaner = cleaner(storage);
        doAnswer(call -> {
            assertThat(item.get().state()).isEqualTo(ItemState.FAILED);
            assertThat(cleaner.runCycle().deleted()).isZero();
            execution.set(new Attempt(3, 2, original.requestId(), original.bindingDigest(), 1, original.objectKey(),
                    AttemptState.INVALIDATED, 2, start, original.leaseUntil(), clock.get()));
            return 1;
        }).when(mapper).finish(3, "INVALIDATED");
        recovery.recover(2);
        assertThat(cleaner.runCycle().deleted()).isEqualTo(1);
        assertThatThrownBy(() -> acceptance.accept("token", 9, original, "text/plain")).hasMessage("IMPORT_CONFLICT");
        // 替身在下一轮再次列举同键，模拟迟到 PUT 及宽限期经过后的残留。
        assertThat(cleaner.runCycle().deleted()).isEqualTo(1);
        verify(storage, times(2)).delete(original.objectKey());
        verifyNoInteractions(documents);
    }

    private StorageReconciliationService cleaner(ObjectStorage storage) throws Exception {
        var references = mock(StorageReconciliationMapper.class);
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        // 另有独立存活版本，保留空集合护栏的正常前提。
        when(references.hasLiveReference()).thenReturn(true);
        when(references.listLiveObjectKeys(any())).thenAnswer(call ->
                committedAttempt.get().state() == AttemptState.ACTIVE || committedItem.get().state() == ItemState.ACCEPTED
                        ? List.of(original.objectKey()) : List.of());
        when(storage.list("documents/", null, 1000)).thenReturn(new ListObjectsPage(
                List.of(new StoredObject(original.objectKey(), start.minusDays(1).toInstant())), null));
        return new StorageReconciliationService(storage, references, source,
                new StorageReconciliationProperties(0, 0, 3600, null, true, 100, false, 0),
                Clock.fixed(start.plusDays(1).toInstant(), ZoneOffset.UTC));
    }

    private void compete(boolean expireDuringWrite) throws Exception {
        clock.set(start.plusSeconds(119));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(documents.create(anyLong(), anyLong(), anyString(), anyShort(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenAnswer(call -> {
                    writing.countDown();
                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                    return new DocumentUploadResult(10L, 20L, 30L, "pending");
                });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var commit = pool.submit(() -> {
                try {
                    acceptance.accept("token", 9, original, "text/plain");
                    return "accepted";
                } catch (RuntimeException failed) {
                    return failed.getMessage();
                }
            });
            try {
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                var recover = pool.submit(() -> recovery.recover(2));
                if (expireDuringWrite) {
                    clock.set(start.plusSeconds(121));
                }
                finish.countDown();
                assertThat(commit.get(5, TimeUnit.SECONDS)).isEqualTo(expireDuringWrite ? "IMPORT_UNAVAILABLE" : "accepted");
                assertThat(recover.get(5, TimeUnit.SECONDS)).isEqualTo(expireDuringWrite);
            } finally {
                finish.countDown();
            }
        }
    }

    private Item row(ItemState state, long revision, Receipt receipt) {
        return new Item(2, 9, "one", 1, "x.txt", "标题", 5, "a".repeat(64), state, revision, 3L,
                receipt == null ? null : receipt.documentId(), receipt == null ? null : receipt.versionId(),
                receipt == null ? null : receipt.parseTaskId(), state == ItemState.FAILED ? FailureCode.EXPIRED : null);
    }

    private final class MemoryTransactions implements PlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private Item beforeItem;
        private Attempt beforeAttempt;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            lock.lock();
            beforeItem = item.get();
            beforeAttempt = execution.get();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committedItem.set(item.get());
            committedAttempt.set(execution.get());
            lock.unlock();
        }

        @Override
        public void rollback(TransactionStatus status) {
            item.set(beforeItem);
            execution.set(beforeAttempt);
            lock.unlock();
        }
    }
}
