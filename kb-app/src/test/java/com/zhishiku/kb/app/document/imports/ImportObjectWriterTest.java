package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.service.ImportObjectWriter;
import com.zhishiku.kb.app.document.imports.support.ImportDeadline;
import com.zhishiku.kb.app.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportObjectWriterTest {
    private final S3Client client = mock(S3Client.class);
    private final ImportObjectWriter writer = new ImportObjectWriter(
            new StorageProperties(null, null, "access", "secret", "original-bucket", true, 100),
            new ImportProperties("100", "1024", "8", "2", "120", "60", "30000", "100"), client);
    private final Item item = new Item(2, 9, "one", 1, "x.txt", "标题", 5, "a".repeat(64),
            ItemState.PROCESSING, 1, 3L, null, null, null, null);

    @Test
    void writesActualBytesToEachAttemptKeyAndClosesStreams() throws Exception {
        List<String> keys = new ArrayList<>();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertThat(request.bucket()).isEqualTo("original-bucket");
            assertThat(request.contentType()).isEqualTo("text/plain");
            assertThat(request.metadata()).containsEntry("sha256", item.sha256());
            assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(3));
            assertThat(body.contentLength()).isEqualTo(5);
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).isEqualTo(new byte[]{1, 2, 3, 4, 5});
            keys.add(request.key());
            return PutObjectResponse.builder().build();
        });
        for (String key : List.of("documents/imports/one", "documents/imports/two")) {
            var input = new CloseTrackingInput();
            writer.put(attempt(key), item, file(input), "text/plain", new ImportDeadline(Duration.ofSeconds(3), () -> 0));
            assertThat(input.closed).isTrue();
        }
        assertThat(keys).containsExactly("documents/imports/one", "documents/imports/two");
    }

    @Test
    void expiredAndTransactionalCallsDoNotStartStorage() throws Exception {
        AtomicLong clock = new AtomicLong();
        var deadline = new ImportDeadline(Duration.ofSeconds(1), clock::get);
        clock.set(Duration.ofSeconds(1).toNanos());
        assertThatThrownBy(() -> writer.put(attempt("documents/a"), item, file(new CloseTrackingInput()), "text/plain", deadline))
                .hasMessage("IMPORT_UNAVAILABLE");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> writer.put(attempt("documents/a"), item, file(new CloseTrackingInput()), "text/plain",
                    new ImportDeadline(Duration.ofSeconds(5)))).hasMessage("IMPORT_UNAVAILABLE");
        } finally {
            TransactionSynchronizationManager.clear();
        }
        verifyNoInteractions(client);
    }

    @Test
    void lateSuccessAndSensitiveFailureAreFixedErrorsAndCloseResources() throws Exception {
        AtomicLong clock = new AtomicLong();
        var deadline = new ImportDeadline(Duration.ofSeconds(1), clock::get);
        var input = new CloseTrackingInput();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return PutObjectResponse.builder().build();
        });
        assertThatThrownBy(() -> writer.put(attempt("documents/a"), item, file(input), "text/plain", deadline))
                .hasMessage("IMPORT_UNAVAILABLE").hasNoCause();
        assertThat(input.closed).isTrue();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("secret-key-file-name"));
        var failedInput = new CloseTrackingInput();
        assertThatThrownBy(() -> writer.put(attempt("documents/b"), item, file(failedInput), "text/plain",
                new ImportDeadline(Duration.ofSeconds(5)))).hasMessage("IMPORT_UNAVAILABLE").hasNoCause();
        assertThat(failedInput.closed).isTrue();
    }

    private MultipartFile file(CloseTrackingInput input) throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(input);
        return file;
    }

    private Attempt attempt(String key) {
        var now = OffsetDateTime.parse("2026-11-08T00:00:00Z");
        return new Attempt(3, 2, "request", "b".repeat(64), 1, key, AttemptState.ACTIVE, 2, now, now.plusSeconds(120), null);
    }

    private static final class CloseTrackingInput extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInput() {
            super(new byte[]{1, 2, 3, 4, 5});
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
