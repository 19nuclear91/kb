package com.zhishiku.kb.app.document.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.BatchState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Counts;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.app.document.imports.service.ImportReadService;
import com.zhishiku.kb.app.document.imports.support.ImportCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportReadServiceTest {
    private final ImportMapper mapper = mock(ImportMapper.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private final ImportReadService service = new ImportReadService(mapper, access);
    private final Batch batch = new Batch(9, 1, "key", "a".repeat(64), 8, (short) 2, 2, 10,
            OffsetDateTime.parse("2026-11-08T00:00:00Z"));

    @BeforeEach
    void setup() {
        when(access.current("token")).thenReturn(new EpochSessionService.Session(1, 1, EpochSessionService.Method.LOCAL, false));
        when(access.canUpload(1, 8)).thenReturn(true);
        when(mapper.batch(1, 9)).thenReturn(batch);
        when(mapper.counts(9)).thenReturn(new Counts(0, 0, 1, 1));
    }

    @Test
    void paginatesAndDerivesStateFromOneCountSnapshot() {
        when(mapper.batches(1, null, 2)).thenReturn(List.of(batch, batch));
        var page = service.list("token", 1, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(ImportCursor.decode(page.nextCursor(), 1, 0)).isEqualTo(9);
        assertThat(page.items().getFirst().state()).isEqualTo(BatchState.PARTIAL_FAILED);
        when(mapper.counts(9)).thenReturn(new Counts(0, 1, 1, 0));
        assertThat(service.detail("token", 9).state()).isEqualTo(BatchState.OPEN);
        when(mapper.counts(9)).thenReturn(new Counts(0, 0, 2, 0));
        assertThat(service.detail("token", 9).state()).isEqualTo(BatchState.COMPLETED);
        when(mapper.counts(9)).thenReturn(new Counts(0, 0, 1, 0));
        assertThatThrownBy(() -> service.detail("token", 9)).hasMessage("IMPORT_UNAVAILABLE");
    }

    @Test
    void emptyListAndFinalPageAreExplicit() {
        when(mapper.batches(1, null, 21)).thenReturn(List.of());
        var page = service.list("token", 20, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void itemPagingBindsOwnerAndBatchWithExactIdentity() {
        when(mapper.items(9, 0, 2)).thenReturn(List.of(item(), item()));
        var page = service.items("token", 9, 1, null);
        assertThat(page.items().getFirst().itemId()).isEqualTo(Long.MAX_VALUE);
        assertThat(ImportCursor.decode(page.nextCursor(), 1, 9)).isEqualTo(1);
        assertThatThrownBy(() -> ImportCursor.decode(page.nextCursor(), 2, 9)).hasMessage("IMPORT_INVALID_INPUT");
        assertThatThrownBy(() -> ImportCursor.decode(page.nextCursor(), 1, 10)).hasMessage("IMPORT_INVALID_INPUT");
        for (String cursor : List.of("", "??", "eA", ImportCursor.encode(1, 9, 501))) {
            assertThatThrownBy(() -> service.items("token", 9, 20, cursor)).hasMessage("IMPORT_INVALID_INPUT");
        }
        assertThatThrownBy(() -> service.list("token", 101, null)).hasMessage("IMPORT_INVALID_INPUT");
    }

    @Test
    void revocationHidesManifestButPreservesAcceptedHistory() {
        when(access.canUpload(1, 8)).thenReturn(false);
        when(mapper.item(9, Long.MAX_VALUE)).thenReturn(item());
        var result = service.item("token", 9, Long.MAX_VALUE);
        assertThat(result.file()).isNull();
        assertThat(result.canUpload()).isFalse();
        assertThat(result.receipt()).isEqualTo(new Receipt(4, 5, 6));
        var tree = new ObjectMapper().valueToTree(result);
        assertThat(tree.has("file")).isFalse();
        assertThat(tree.get("itemId").longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(service.detail("token", 9).spaceId()).isNull();
    }

    @Test
    void unknownAndOtherOwnerAreNotReadableAndFailuresAreNotEmpty() {
        assertThatThrownBy(() -> service.detail("token", 10)).hasMessage("IMPORT_NOT_FOUND");
        when(access.current("other")).thenReturn(new EpochSessionService.Session(2, 1, EpochSessionService.Method.LOCAL, false));
        assertThatThrownBy(() -> service.items("other", 9, 20, null)).hasMessage("IMPORT_NOT_FOUND");
        verify(mapper, never()).items(anyLong(), anyInt(), anyInt());
        when(access.canUpload(1, 8)).thenThrow(new ImportException(ImportException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> service.detail("token", 9)).hasMessage("IMPORT_UNAVAILABLE");
    }

    @Test
    void aNewValidSessionReadsSameHistoryWithoutOldToken() {
        when(access.current("new-session")).thenReturn(new EpochSessionService.Session(1, 5, EpochSessionService.Method.LOCAL, false));
        assertThat(service.detail("new-session", 9).importId()).isEqualTo(9);
    }

    private Item item() {
        return new Item(Long.MAX_VALUE, 9, "one", 1, "原件.txt", "标题", 5, "a".repeat(64),
                ItemState.ACCEPTED, 2, 3L, 4L, 5L, 6L, null);
    }
}
