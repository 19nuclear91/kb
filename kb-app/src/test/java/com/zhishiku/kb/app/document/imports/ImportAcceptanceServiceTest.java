package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportAcceptanceService;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.app.document.mapper.DocumentWriteMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentVersionEntity;
import com.zhishiku.kb.app.document.model.ParseTaskEntity;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spring 真实事务代理与内存提交替身，只验证协作及失败分支。 */
class ImportAcceptanceServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private final DocumentWriteMapper writes = mock(DocumentWriteMapper.class);
    private final MemoryTransactions manager = new MemoryTransactions();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-08T00:00:00Z");
    private final Attempt attempt = new Attempt(3, 2, "request", "a".repeat(64), 1,
            "documents/imports/one.txt", AttemptState.ACTIVE, 2, now, now.plusSeconds(120), null);
    private final List<Object> persisted = new ArrayList<>();
    private ImportAcceptanceService service;
    private String spacePolicy = "AUTO";

    @BeforeEach
    void setup() {
        var actor = new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false);
        when(access.current("token")).thenReturn(actor);
        when(access.requireUpload("token", 8)).thenReturn(actor);
        when(mapper.batch(1, 9)).thenReturn(new Batch(9, 1, "create", "a".repeat(64), 8, (short) 0, 1, 5, now));
        when(mapper.lockItem(2)).thenReturn(new Item(2, 9, "one", 1, "x.txt", "标题", 5, "a".repeat(64),
                ItemState.PROCESSING, 1, 3L, null, null, null, null));
        when(mapper.shareSpace(8)).thenReturn(8L);
        when(mapper.attempt(3)).thenReturn(attempt);
        when(mapper.now()).thenReturn(now.plusSeconds(10));
        when(writes.insertDocument(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            DocumentEntity document = call.getArgument(0);
            assertThat(document.getSpaceId()).isEqualTo(8);
            document.setId(10L);
            persisted.add(document);
            return 1;
        });
        when(writes.insertVersion(any())).thenAnswer(call -> {
            DocumentVersionEntity version = call.getArgument(0);
            assertThat(version.getPublicationPolicy()).isNull();
            version.setPublicationPolicy(spacePolicy);
            version.setId(20L);
            persisted.add(version);
            return 1;
        });
        when(writes.insertParseTask(any())).thenAnswer(call -> {
            ParseTaskEntity task = call.getArgument(0);
            task.setId(30L);
            persisted.add(task);
            return 1;
        });
        when(mapper.accept(eq(2L), eq(3L), eq(1L), any())).thenAnswer(call -> {
            persisted.add(call.getArgument(3));
            return 1;
        });
        when(mapper.finish(3, "ACCEPTED")).thenReturn(1);
        var factory = new ProxyFactory(new DocumentWriteService(writes));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = new ImportAcceptanceService(mapper, access, (DocumentWriteService) factory.getProxy(), manager);
    }

    @Test
    void documentWriterJoinsReceiptTransactionAndReadsActualPolicyAtWrite() {
        spacePolicy = "REVIEW_REQUIRED";
        assertThat(service.accept("token", 9, attempt, "text/plain")).isEqualTo(new Receipt(10, 20, 30));
        assertThat(manager.begins).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
        assertThat(persisted).hasSize(4);
        assertThat(((DocumentVersionEntity) persisted.get(1)).getPublicationPolicy()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void receiptAndAttemptWriteFailuresRollBackAllEarlierRows() {
        when(mapper.accept(eq(2L), eq(3L), eq(1L), any())).thenReturn(0);
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_UNAVAILABLE");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(persisted).isEmpty();
        when(mapper.accept(eq(2L), eq(3L), eq(1L), any())).thenReturn(1);
        when(mapper.finish(3, "ACCEPTED")).thenThrow(new IllegalStateException("injected failure"));
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).isInstanceOf(IllegalStateException.class);
        assertThat(manager.rollbacks).isEqualTo(2);
        assertThat(persisted).isEmpty();
    }

    @Test
    void epochChangeAndRevocationPreventAllDocumentWrites() {
        when(access.requireUpload("token", 8)).thenReturn(new EpochSessionService.Session(1, 3, EpochSessionService.Method.LOCAL, false));
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_FORBIDDEN");
        doThrow(new ImportException(ImportException.Reason.FORBIDDEN)).when(access).requireUpload("token", 8);
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_FORBIDDEN");
        verifyNoInteractions(writes);
    }

    @Test
    void archivedSpaceExpiredOrReplacedAttemptCannotCommit() {
        when(mapper.shareSpace(8)).thenReturn(null);
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_FORBIDDEN");
        when(mapper.shareSpace(8)).thenReturn(8L);
        when(mapper.now()).thenReturn(attempt.leaseUntil());
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_CONFLICT");
        when(mapper.attempt(3)).thenReturn(null);
        assertThatThrownBy(() -> service.accept("token", 9, attempt, "text/plain")).hasMessage("IMPORT_CONFLICT");
        verifyNoInteractions(writes);
    }

    private final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Holder> current = new ThreadLocal<>();
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return current.get() == null ? new Holder() : current.get();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Holder) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            Holder holder = (Holder) transaction;
            holder.active = true;
            holder.before = new ArrayList<>(persisted);
            current.set(holder);
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            persisted.clear();
            persisted.addAll(((Holder) status.getTransaction()).before);
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }
    }

    private static final class Holder {
        private boolean active;
        private List<Object> before;
    }
}
