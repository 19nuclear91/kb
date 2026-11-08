package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.service.ImportFileChecks;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportFileChecksTest {
    @Test
    void ignoresDeclaredMimeAndUsesActualTextFormat() throws Exception {
        var file = new MockMultipartFile("file", " x.txt ", "text/html", "hello".getBytes(StandardCharsets.UTF_8));
        var expected = intent("x.txt", "hello");
        ImportFileChecks.match(file, expected, 50);
        assertThat(ImportFileChecks.inspect(file, expected, 50)).isEqualTo("text/plain");
    }

    @Test
    void sameNameAndSizeWithChangedContentsConflicts() throws Exception {
        var file = new MockMultipartFile("file", "x.txt", null, "world".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ImportFileChecks.match(file, intent("x.txt", "hello"), 50)).hasMessage("IMPORT_CONFLICT");
    }

    @Test
    void forgedPdfFailsAfterContentBindingMatched() throws Exception {
        var file = new MockMultipartFile("file", "x.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8));
        var expected = intent("x.pdf", "hello");
        ImportFileChecks.match(file, expected, 50);
        assertThatThrownBy(() -> ImportFileChecks.inspect(file, expected, 50)).hasMessage("IMPORT_INVALID_FILE");
    }

    @Test
    void checksEmptyAndActualBytesRatherThanMetadataOnly() throws Exception {
        var expected = intent("x.txt", "hello");
        assertThatThrownBy(() -> ImportFileChecks.match(new MockMultipartFile("file", new byte[0]), expected, 50))
                .hasMessage("IMPORT_INVALID_FILE");
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(5L);
        when(file.getOriginalFilename()).thenReturn("x.txt");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello!".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> ImportFileChecks.match(file, expected, 5)).hasMessage("IMPORT_PAYLOAD_TOO_LARGE");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hell".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> ImportFileChecks.match(file, expected, 50)).hasMessage("IMPORT_CONFLICT");
    }

    private FileIntent intent(String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new FileIntent("one", fileName, "标题", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void cancellationIsNotRecordedAsPermanentInvalidContent() throws Exception {
        var expected = intent("x.txt", "hello");
        var file = new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ImportFileChecks.inspect(file, expected, 50)).hasMessage("IMPORT_UNAVAILABLE");
        } finally {
            Thread.interrupted();
        }
    }
}
