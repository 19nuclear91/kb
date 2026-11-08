package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportAcceptanceService;
import com.zhishiku.kb.app.document.imports.service.ImportAttemptService;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.app.document.imports.service.ImportFailureService;
import com.zhishiku.kb.app.document.imports.service.ImportObjectWriter;
import com.zhishiku.kb.app.document.imports.service.ImportUploadService;
import com.zhishiku.kb.app.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportUploadServiceTest {
    private final ImportAttemptService attempts = mock(ImportAttemptService.class);
    private final ImportAcceptanceService acceptance = mock(ImportAcceptanceService.class);
    private final ImportFailureService failures = mock(ImportFailureService.class);
    private final ImportObjectWriter objects = mock(ImportObjectWriter.class);
    private final AtomicBoolean accepted = new AtomicBoolean();
    private final Receipt receipt = new Receipt(10, 20, 30);
    private final Item item = new Item(2, 9, "one", 1, "x.txt", "标题", 5,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ItemState.PENDING, 0, null, null, null, null, null);
    private Attempt attempt;
    private ImportUploadService service;

    @BeforeEach
    void setup() {
        var now = OffsetDateTime.parse("2026-11-08T00:00:00Z");
        attempt = new Attempt(3, 2, "key", "a".repeat(64), 1, "documents/imports/one.txt", AttemptState.ACTIVE,
                2, now, now.plusSeconds(120), null);
        var batch = new Batch(9, 1, "create", "a".repeat(64), 8, (short) 0, 1, 5, now);
        when(attempts.preflight("token", 9, 2)).thenReturn(item);
        when(attempts.begin("token", 9, 2, "key", 0)).thenAnswer(call ->
                new ImportAttemptService.Started(batch, item, accepted.get() ? null : attempt, accepted.get() ? receipt : null));
        when(acceptance.accept("token", 9, attempt, "text/plain")).thenReturn(receipt);
        service = new ImportUploadService(attempts, acceptance, failures, objects,
                new ImportProperties("100", "1024", "8", "2", "120", "60", "30000", "100"),
                new StorageProperties(null, null, null, null, null, true, 100));
    }

    @Test
    void committedButLostResponseReplaysWithoutASecondPutOrCreation() {
        when(acceptance.accept("token", 9, attempt, "text/plain")).thenAnswer(call -> {
            accepted.set(true);
            throw new IllegalStateException("commit acknowledgement lost");
        });
        assertThatThrownBy(() -> upload("hello")).isInstanceOf(IllegalStateException.class);
        assertThat(upload("hello")).isEqualTo(receipt);
        verify(objects, times(1)).put(any(), any(), any(), anyString(), any());
        verify(acceptance, times(1)).accept(anyString(), anyLong(), any(), anyString());
        verifyNoInteractions(failures);
    }

    @Test
    void failureBeforeAcceptanceInvalidatesOnlyThatAttempt() {
        doThrow(new IllegalStateException("private storage exception")).when(objects).put(any(), any(), any(), anyString(), any());
        assertThatThrownBy(() -> upload("hello")).hasMessage("IMPORT_UNAVAILABLE").hasNoCause();
        verify(failures).invalidate(attempt, FailureCode.STORAGE_UNAVAILABLE);
        verifyNoInteractions(acceptance);
    }

    @Test
    void failureRecordUnknownKeepsAnUnknownResponse() {
        doThrow(new ImportException(ImportException.Reason.UNAVAILABLE)).when(objects).put(any(), any(), any(), anyString(), any());
        when(failures.invalidate(attempt, FailureCode.STORAGE_UNAVAILABLE)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> upload("hello")).hasMessage("IMPORT_UNAVAILABLE").hasNoCause();
        verifyNoInteractions(acceptance);
    }

    @Test
    void mismatchedContentCannotEvenClaimAnExecution() {
        assertThatThrownBy(() -> upload("world")).hasMessage("IMPORT_CONFLICT");
        verifyNoInteractions(objects, acceptance, failures);
        verify(attempts, times(0)).begin(anyString(), anyLong(), anyLong(), anyString(), anyLong());
    }

    private Receipt upload(String body) {
        return service.upload("token", 9, 2, "key", 0,
                new MockMultipartFile("file", "x.txt", "text/html", body.getBytes(StandardCharsets.UTF_8)));
    }
}
