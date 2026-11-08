package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportFailureService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportFailureServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ImportFailureService service = new ImportFailureService(mapper, manager);

    @Test
    void invalidationAndFailureShareTransactionAndRollbackOnFailure() {
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        when(mapper.lockItem(2)).thenReturn(item(ItemState.PROCESSING, 1, 3));
        when(mapper.attempt(3)).thenReturn(attempt());
        when(mapper.fail(2, 3, 1, FailureCode.STORAGE_UNAVAILABLE)).thenReturn(1);
        when(mapper.finish(3, "INVALIDATED")).thenReturn(1);
        assertThat(service.invalidate(attempt(), FailureCode.STORAGE_UNAVAILABLE)).isTrue();
        verify(manager).commit(status);
        when(mapper.finish(3, "INVALIDATED")).thenReturn(0);
        assertThatThrownBy(() -> service.invalidate(attempt(), FailureCode.STORAGE_UNAVAILABLE)).hasMessage("IMPORT_UNAVAILABLE");
        verify(manager).rollback(status);
    }

    @Test
    void oldFailureCannotOverwriteNewExecutionOrAcceptedReceipt() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.attempt(3)).thenReturn(attempt());
        when(mapper.lockItem(2)).thenReturn(item(ItemState.PROCESSING, 3, 4));
        assertThat(service.invalidate(attempt(), FailureCode.STORAGE_UNAVAILABLE)).isFalse();
        when(mapper.lockItem(2)).thenReturn(item(ItemState.ACCEPTED, 4, 4));
        assertThat(service.invalidate(attempt(), FailureCode.STORAGE_UNAVAILABLE)).isFalse();
        verify(mapper, never()).fail(anyLong(), anyLong(), anyLong(), any());
        verify(mapper, never()).finish(anyLong(), any());
    }

    private Item item(ItemState state, long revision, long attemptId) {
        return new Item(2, 9, "one", 1, "x.txt", "标题", 5, "a".repeat(64), state, revision, attemptId,
                state == ItemState.ACCEPTED ? 10L : null, state == ItemState.ACCEPTED ? 20L : null,
                state == ItemState.ACCEPTED ? 30L : null, null);
    }

    private Attempt attempt() {
        var now = OffsetDateTime.parse("2026-11-08T00:00:00Z");
        return new Attempt(3, 2, "key", "a".repeat(64), 1, "documents/one", AttemptState.ACTIVE, 2, now, now.plusSeconds(120), null);
    }
}
